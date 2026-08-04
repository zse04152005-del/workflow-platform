package com.workflowplatform.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowplatform.controlplane.runtime.LeaseRecoveryService;
import com.workflowplatform.controlplane.runtime.OutboxPublicationService;
import com.workflowplatform.controlplane.runtime.RuntimeQueryService;
import com.workflowplatform.controlplane.runtime.RuntimeQueryStore;
import com.workflowplatform.controlplane.runtime.RuntimeSchedulingService;
import com.workflowplatform.controlplane.runtime.RuntimeTriggerResult;
import com.workflowplatform.controlplane.runtime.RuntimeTriggerService;
import com.workflowplatform.controlplane.runtime.internal.KafkaOutboxEventSink;
import com.workflowplatform.controlplane.security.WorkflowRequestContext;
import com.workflowplatform.worker.internal.KafkaTaskReadyConsumer;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class RuntimeHappyPathE2EIT {

    private static final String POSTGRES_IMAGE =
            "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296";
    private static final String KAFKA_IMAGE =
            "apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";
    private static final String DATABASE = "workflow_g4_e2e";
    private static final String ADMIN_USER = "workflow_e2e_admin";
    private static final String MIGRATION_USER = "workflow_e2e_migration";
    private static final String APPLICATION_USER = "workflow_e2e_app";
    private static final String PLATFORM_ADMIN_ROLE = "workflow_e2e_platform_admin";
    private static final String TASK_TOPIC = "workflow.task.ready.e2e";
    private static final String EVENTS_TOPIC = "workflow.events.e2e";
    private static final String CONSUMER_GROUP = "workflow-worker-e2e";
    private static final String TRACE_ID = "abcdef1234567890abcdef1234567890";
    private static final String DIGEST = "7".repeat(64);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ADMIN_CREDENTIAL = randomCredential();
    private static final String MIGRATION_CREDENTIAL = randomCredential();
    private static final String APPLICATION_CREDENTIAL = randomCredential();
    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c801");
    private static final UUID PROJECT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c802");
    private static final UUID DEFINITION_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c803");
    private static final UUID VERSION_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c804");
    private static final UUID CONNECTOR_ID =
            UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c805");
    private static final WorkflowRequestContext CONTEXT =
            new WorkflowRequestContext(TENANT_ID, "g4-e2e");
    private static final String DSL =
            """
            {
              "schemaVersion":1,
              "name":"http-condition-http-delay-end",
              "catalogVersion":1,
              "triggers":[{"id":"http-trigger","type":"http"}],
              "nodes":[
                {"id":"start","type":"start","executorVersion":1},
                {"id":"condition","type":"condition","executorVersion":1,
                 "config":{"expression":"input.amount <= 500"}},
                {"id":"managed-http","type":"http","executorVersion":1,"timeout":"PT5S",
                 "config":{"connectorRef":"https.order-api","method":"POST",
                 "pathTemplate":"orders","headers":{"X-Order-Id":"input.orderId"},
                 "bodyExpression":"input","resultExpression":"response.body.id"}},
                {"id":"delay","type":"delay","executorVersion":1,
                 "config":{"duration":"PT1S"}},
                {"id":"end","type":"end","executorVersion":1},
                {"id":"fallback-end","type":"end","executorVersion":1}
              ],
              "edges":[
                {"id":"start-condition","from":"start","to":"condition","on":"success"},
                {"id":"approved","from":"condition","to":"managed-http","on":"condition",
                 "when":"input.amount <= 500"},
                {"id":"fallback","from":"condition","to":"fallback-end","on":"always"},
                {"id":"http-delay","from":"managed-http","to":"delay","on":"success"},
                {"id":"delay-end","from":"delay","to":"end","on":"success"}
              ]
            }
            """;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(POSTGRES_IMAGE)
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName(DATABASE)
                    .withUsername(ADMIN_USER)
                    .withPassword(ADMIN_CREDENTIAL)
                    .withLabel("com.workflowplatform.test-suite", "G4-17")
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withReuse(false);

    @Container
    private static final KafkaContainer KAFKA =
            new KafkaContainer(
                            DockerImageName.parse(KAFKA_IMAGE)
                                    .asCompatibleSubstituteFor("apache/kafka"))
                    .withLabel("com.workflowplatform.test-suite", "G4-17")
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withReuse(false);

    private static JdbcTemplate admin;

    @BeforeAll
    static void provisionSeedAndCreateTopics() throws Exception {
        provisionRolesAndMigrate();
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_CREDENTIAL));
        seedWorkflow();
        try (Admin kafkaAdmin = Admin.create(adminProperties())) {
            kafkaAdmin
                    .createTopics(
                            List.of(
                                    new NewTopic(TASK_TOPIC, 1, (short) 1),
                                    new NewTopic(EVENTS_TOPIC, 1, (short) 1)))
                    .all()
                    .get(20, TimeUnit.SECONDS);
        }
    }

    @Test
    void httpConditionManagedHttpDelayRestartAndEndUseRealDatabaseAndKafka() throws Exception {
        AtomicInteger managedHttpCalls = new AtomicInteger();
        try (AnnotationConfigApplicationContext workerStoreContext = workerStoreContext()) {
            WorkerExecutionService worker = worker(workerStoreContext, managedHttpCalls);
            KafkaMessageListenerContainer<String, String> listener = startWorkerListener(worker);
            try {
                UUID instanceId;
                try (ControlRuntime control = controlRuntime()) {
                    RuntimeTriggerResult first = trigger(control.triggers(), "g4-e2e-key");
                    RuntimeTriggerResult replay = trigger(control.triggers(), "g4-e2e-key");
                    instanceId = first.instanceId();
                    assertEquals(instanceId, replay.instanceId());
                    assertTrue(replay.replayed());

                    executeNext(control, instanceId, "start", "SUCCEEDED");
                    executeNext(control, instanceId, "condition", "SUCCEEDED");
                    assertEquals("CANCELLED", nodeStatus(instanceId, "fallback-end"));
                    executeNext(control, instanceId, "managed-http", "SUCCEEDED");
                    executeNext(control, instanceId, "delay", "WAITING_TIMER");

                    String persistedResumeAt =
                            admin.queryForObject(
                                    "SELECT next_schedule_at::text FROM public.node_instance "
                                            + "WHERE workflow_instance_id = ? AND node_id = 'delay'",
                                    String.class,
                                    instanceId);
                    assertNotNull(persistedResumeAt);
                }

                try (ControlRuntime control = controlRuntime()) {
                    control.scheduler()
                            .dispatchDue(
                                    CONTEXT,
                                    "scheduler-after-restart",
                                    Instant.now().plusSeconds(5),
                                    100,
                                    Duration.ofMinutes(2),
                                    TRACE_ID);
                    publishAll(control);
                    awaitNodeStatus(instanceId, "delay", "SUCCEEDED");

                    executeNext(control, instanceId, "end", "SUCCEEDED");
                    control.scheduler().progress(CONTEXT, instanceId, TRACE_ID);
                    awaitInstanceStatus(instanceId, "SUCCEEDED");

                    RuntimeQueryStore.InstanceView instance =
                            control.queries().instance(CONTEXT, instanceId);
                    assertEquals("SUCCEEDED", instance.status());
                    assertEquals(
                            6,
                            control.queries().nodes(CONTEXT, instanceId, null, 20).items().size());
                    assertEquals(
                            6,
                            control.queries()
                                    .attempts(CONTEXT, instanceId, null, 20)
                                    .items()
                                    .size());
                    assertEquals(1, managedHttpCalls.get());
                    assertEquals(
                            6,
                            count(
                                    "SELECT count(*) FROM public.processed_event "
                                            + "WHERE tenant_id = ? AND consumer_name = ?",
                                    TENANT_ID,
                                    CONSUMER_GROUP));
                    assertEquals(
                            "https://api.example.test/v1/",
                            admin.queryForObject(
                                    "SELECT config_snapshot->>'_managedEndpoint' "
                                            + "FROM public.node_instance WHERE workflow_instance_id = ? "
                                            + "AND node_id = 'managed-http'",
                                    String.class,
                                    instanceId));
                }
            } finally {
                listener.stop();
                worker.stopNodeExecutions();
            }
        }
    }

    @Test
    void killedWorkerLeaseIsRecoveredAndItsLateResultIsFenced() throws Exception {
        UUID instanceId;
        UUID nodeInstanceId;
        WorkerTaskStore.ClaimedTask abandoned;
        try (ControlRuntime control = controlRuntime()) {
            instanceId = trigger(control.triggers(), "g5-worker-kill-key").instanceId();
            control.scheduler().progress(CONTEXT, instanceId, TRACE_ID);
            control.scheduler()
                    .dispatchDue(
                            CONTEXT,
                            "scheduler-before-kill",
                            Instant.now().plusSeconds(1),
                            100,
                            Duration.ofMillis(100),
                            TRACE_ID);
            nodeInstanceId = nodeInstanceId(instanceId, "start");
            try (AnnotationConfigApplicationContext killedWorker = workerStoreContext()) {
                abandoned =
                        killedWorker
                                .getBean(WorkerTaskStore.class)
                                .claim(
                                        taskEvent(UUID.randomUUID(), nodeInstanceId),
                                        "workflow-worker-kill-e2e",
                                        "worker-before-kill")
                                .task();
                assertEquals(1, abandoned.attemptNo());
            }

            Thread.sleep(150L);
            var recovered =
                    control.recoveries()
                            .recoverExpired(
                                    CONTEXT, "scheduler-after-kill", Instant.now(), 100, TRACE_ID);
            assertEquals(1, recovered.size());
            assertTrue(recovered.getFirst().abandonedAttempt());
            assertEquals("READY", nodeStatus(instanceId, "start"));
            assertEquals(
                    "ABANDONED",
                    admin.queryForObject(
                            "SELECT status FROM public.task_execution WHERE execution_id = ?",
                            String.class,
                            abandoned.executionId()));

            control.scheduler()
                    .dispatchDue(
                            CONTEXT,
                            "scheduler-takeover",
                            Instant.now().plusSeconds(1),
                            100,
                            Duration.ofMinutes(2),
                            TRACE_ID);
        }

        try (AnnotationConfigApplicationContext replacementWorker = workerStoreContext()) {
            WorkerTaskStore store = replacementWorker.getBean(WorkerTaskStore.class);
            WorkerTaskStore.NodeExecutionResult success =
                    WorkerTaskStore.NodeExecutionResult.success("inline:recovered", DIGEST);
            assertThrows(
                    IllegalStateException.class,
                    () -> store.complete(abandoned, "worker-before-kill", success));

            WorkerTaskStore.ClaimedTask replacement =
                    store.claim(
                                    taskEvent(UUID.randomUUID(), nodeInstanceId),
                                    "workflow-worker-kill-e2e",
                                    "worker-after-kill")
                            .task();
            assertEquals(2, replacement.attemptNo());
            assertEquals(2, replacement.leaseGeneration());
            store.complete(replacement, "worker-after-kill", success);
        }

        assertEquals("SUCCEEDED", nodeStatus(instanceId, "start"));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.task_execution WHERE node_instance_id = ? "
                                + "AND attempt_no = 1 AND status = 'ABANDONED'",
                        nodeInstanceId));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.task_execution WHERE node_instance_id = ? "
                                + "AND attempt_no = 2 AND status = 'SUCCEEDED'",
                        nodeInstanceId));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.worker_lease WHERE node_instance_id = ? "
                                + "AND lease_generation = 1 AND status = 'EXPIRED'",
                        nodeInstanceId));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM public.worker_lease WHERE node_instance_id = ? "
                                + "AND lease_generation = 2 AND status = 'RELEASED'",
                        nodeInstanceId));
        assertEquals(
                "2:2:2",
                admin.queryForObject(
                        "SELECT dispatch_count || ':' || attempt_no || ':' || lease_generation "
                                + "FROM public.node_instance WHERE id = ?",
                        String.class,
                        nodeInstanceId));
    }

    private static RuntimeTriggerResult trigger(RuntimeTriggerService triggers, String key)
            throws Exception {
        return triggers.http(
                CONTEXT,
                DEFINITION_ID,
                new ObjectMapper().readTree("{\"orderId\":\"order-1001\",\"amount\":100}"),
                "order/order-1001",
                key,
                TRACE_ID);
    }

    private static WorkerTaskStore.TaskReadyEvent taskEvent(UUID eventId, UUID nodeInstanceId) {
        return new WorkerTaskStore.TaskReadyEvent(
                eventId, TENANT_ID, nodeInstanceId, "workflow.task.ready", 1, TRACE_ID);
    }

    private static void executeNext(
            ControlRuntime control, UUID instanceId, String nodeId, String expectedStatus)
            throws Exception {
        control.scheduler().progress(CONTEXT, instanceId, TRACE_ID);
        control.scheduler()
                .dispatchDue(
                        CONTEXT,
                        "scheduler-e2e",
                        Instant.now().plusSeconds(2),
                        100,
                        Duration.ofMinutes(2),
                        TRACE_ID);
        publishAll(control);
        awaitNodeStatus(instanceId, nodeId, expectedStatus);
    }

    private static void publishAll(ControlRuntime control) {
        List<OutboxPublicationService.PublicationResult> published =
                control.outbox()
                        .publishBatch(
                                CONTEXT,
                                "publisher-e2e",
                                Instant.now().plusSeconds(5),
                                500,
                                Duration.ofMinutes(2),
                                control.eventSink());
        assertFalse(published.isEmpty());
        assertTrue(
                published.stream()
                        .allMatch(
                                result ->
                                        result.state()
                                                == OutboxPublicationService.PublicationState
                                                        .PUBLISHED));
    }

    private static WorkerExecutionService worker(
            AnnotationConfigApplicationContext context, AtomicInteger managedHttpCalls)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ManagedHttpWorkerNodeExecutor managedHttp =
                new ManagedHttpWorkerNodeExecutor(
                        mapper,
                        Set.of("api.example.test"),
                        4096,
                        ignored -> List.of(InetAddress.getByAddress(new byte[] {8, 8, 8, 8})),
                        (request, policy, maximumBytes) -> {
                            policy.validate(request.uri());
                            managedHttpCalls.incrementAndGet();
                            assertEquals("POST", request.method());
                            assertEquals("order-1001", request.headers().get("X-Order-Id"));
                            assertTrue(
                                    new String(request.body(), StandardCharsets.UTF_8)
                                            .contains("\"amount\":100"));
                            assertEquals(4096, maximumBytes);
                            return new ManagedHttpWorkerNodeExecutor.Response(
                                    200, "{\"id\":\"accepted\"}".getBytes(StandardCharsets.UTF_8));
                        });
        return new WorkerExecutionService(
                context.getBean(WorkerTaskStore.class),
                mapper,
                List.of(new BuiltinWorkerNodeExecutor(mapper), managedHttp),
                CONSUMER_GROUP,
                "worker-e2e");
    }

    private static AnnotationConfigApplicationContext workerStoreContext() {
        DriverManagerDataSource dataSource = applicationDataSource();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        context.registerBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.scan("com.workflowplatform.worker.internal");
        context.refresh();
        return context;
    }

    private static ControlRuntime controlRuntime() {
        DriverManagerDataSource dataSource = applicationDataSource();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "g4-e2e",
                                Map.of(
                                        "workflow.kafka.publisher-enabled",
                                        "true",
                                        "workflow.kafka.task-ready-topic",
                                        TASK_TOPIC,
                                        "workflow.kafka.events-topic",
                                        EVENTS_TOPIC)));
        DefaultKafkaProducerFactory<String, String> producers =
                new DefaultKafkaProducerFactory<>(producerProperties());
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        context.registerBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(
                "kafkaTemplate",
                KafkaTemplate.class,
                () -> new KafkaTemplate<String, String>(producers),
                bean -> bean.setDestroyMethodName("destroy"));
        context.scan("com.workflowplatform.controlplane.runtime");
        context.refresh();
        return new ControlRuntime(
                context,
                producers,
                context.getBean(RuntimeTriggerService.class),
                context.getBean(RuntimeSchedulingService.class),
                context.getBean(LeaseRecoveryService.class),
                context.getBean(OutboxPublicationService.class),
                context.getBean(KafkaOutboxEventSink.class),
                context.getBean(RuntimeQueryService.class));
    }

    private static KafkaMessageListenerContainer<String, String> startWorkerListener(
            WorkerExecutionService worker) {
        ContainerProperties properties = new ContainerProperties(TASK_TOPIC);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        KafkaTaskReadyConsumer consumer = new KafkaTaskReadyConsumer(worker);
        properties.setMessageListener(
                (MessageListener<String, String>) record -> consumer.consume(record.value()));
        KafkaMessageListenerContainer<String, String> container =
                new KafkaMessageListenerContainer<>(
                        new DefaultKafkaConsumerFactory<>(consumerProperties()), properties);
        container.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        container.start();
        return container;
    }

    private static void awaitNodeStatus(UUID instanceId, String nodeId, String expected)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            if (expected.equals(nodeStatus(instanceId, nodeId))) {
                return;
            }
            Thread.sleep(50L);
        }
        assertEquals(expected, nodeStatus(instanceId, nodeId));
    }

    private static void awaitInstanceStatus(UUID instanceId, String expected)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            if (expected.equals(instanceStatus(instanceId))) {
                return;
            }
            Thread.sleep(50L);
        }
        assertEquals(expected, instanceStatus(instanceId));
    }

    private static String nodeStatus(UUID instanceId, String nodeId) {
        return admin.queryForObject(
                "SELECT status FROM public.node_instance WHERE workflow_instance_id = ? "
                        + "AND node_id = ?",
                String.class,
                instanceId,
                nodeId);
    }

    private static UUID nodeInstanceId(UUID instanceId, String nodeId) {
        return admin.queryForObject(
                "SELECT id FROM public.node_instance WHERE workflow_instance_id = ? "
                        + "AND node_id = ?",
                UUID.class,
                instanceId,
                nodeId);
    }

    private static String instanceStatus(UUID instanceId) {
        return admin.queryForObject(
                "SELECT status FROM public.workflow_instance WHERE id = ?",
                String.class,
                instanceId);
    }

    private static int count(String sql, Object... arguments) {
        Long value = admin.queryForObject(sql, Long.class, arguments);
        return Math.toIntExact(value == null ? 0L : value);
    }

    private static DriverManagerDataSource applicationDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), APPLICATION_USER, APPLICATION_CREDENTIAL);
    }

    private static Map<String, Object> producerProperties() {
        return Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.ACKS_CONFIG,
                "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                true);
    }

    private static Map<String, Object> consumerProperties() {
        return Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                CONSUMER_GROUP,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false);
    }

    private static Map<String, Object> adminProperties() {
        return Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    }

    private static void provisionRolesAndMigrate() throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(loginRole(MIGRATION_USER, MIGRATION_CREDENTIAL));
            statement.execute(loginRole(APPLICATION_USER, APPLICATION_CREDENTIAL));
            statement.execute("CREATE ROLE " + PLATFORM_ADMIN_ROLE + " NOLOGIN NOBYPASSRLS");
            statement.execute("ALTER DATABASE " + DATABASE + " OWNER TO " + MIGRATION_USER);
            statement.execute("REVOKE ALL ON DATABASE " + DATABASE + " FROM PUBLIC");
            statement.execute("GRANT CONNECT ON DATABASE " + DATABASE + " TO " + MIGRATION_USER);
            statement.execute("GRANT CONNECT ON DATABASE " + DATABASE + " TO " + APPLICATION_USER);
            statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_USER);
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + MIGRATION_USER);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + APPLICATION_USER);
        }
        Path repositoryRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(repositoryRoot.resolve("control-plane"))) {
            repositoryRoot = repositoryRoot.getParent();
        }
        Flyway flyway =
                Flyway.configure()
                        .dataSource(POSTGRES.getJdbcUrl(), MIGRATION_USER, MIGRATION_CREDENTIAL)
                        .locations(
                                "filesystem:"
                                        + repositoryRoot
                                                .resolve(
                                                        "control-plane/src/main/resources/db/migration")
                                                .toAbsolutePath())
                        .placeholders(
                                Map.of(
                                        "workflowApplicationRole", APPLICATION_USER,
                                        "workflowPlatformAdminRole", PLATFORM_ADMIN_ROLE))
                        .cleanDisabled(true)
                        .load();
        assertEquals(7, flyway.migrate().migrationsExecuted);
    }

    private static void seedWorkflow() {
        admin.update(
                "INSERT INTO public.tenant (id, created_by, updated_by, name, normalized_name, status) "
                        + "VALUES (?, 'g4-e2e', 'g4-e2e', 'e2e-tenant', 'e2e-tenant', 'ACTIVE')",
                TENANT_ID);
        admin.update(
                "INSERT INTO public.project (id, tenant_id, created_by, updated_by, name, "
                        + "normalized_name, status) VALUES (?, ?, 'g4-e2e', 'g4-e2e', "
                        + "'e2e-project', 'e2e-project', 'ACTIVE')",
                PROJECT_ID,
                TENANT_ID);
        admin.update(
                "INSERT INTO public.connector (id, tenant_id, created_by, updated_by, project_id, "
                        + "name, normalized_name, connector_type, endpoint, config, status) "
                        + "VALUES (?, ?, 'g4-e2e', 'g4-e2e', ?, 'order-api', 'order-api', "
                        + "'https', 'https://api.example.test/v1/', '{}'::jsonb, 'ACTIVE')",
                CONNECTOR_ID,
                TENANT_ID,
                PROJECT_ID);
        admin.update(
                "INSERT INTO public.workflow_definition (id, tenant_id, created_by, updated_by, "
                        + "project_id, name, normalized_name, status, draft_dsl, draft_checksum) "
                        + "VALUES (?, ?, 'g4-e2e', 'g4-e2e', ?, 'g4-e2e', 'g4-e2e', "
                        + "'ACTIVE', CAST(? AS jsonb), ?)",
                DEFINITION_ID,
                TENANT_ID,
                PROJECT_ID,
                DSL,
                DIGEST);
        admin.update(
                "INSERT INTO public.workflow_version (id, tenant_id, created_by, definition_id, "
                        + "version_no, schema_version, dsl, checksum, executor_catalog_version, "
                        + "published_at, published_by) VALUES (?, ?, 'g4-e2e', ?, 1, 1, "
                        + "CAST(? AS jsonb), ?, '1', transaction_timestamp(), 'g4-e2e')",
                VERSION_ID,
                TENANT_ID,
                DEFINITION_ID,
                DSL,
                DIGEST);
    }

    private static String loginRole(String name, String credential) {
        return "CREATE ROLE "
                + name
                + " LOGIN PASSWORD '"
                + credential
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS";
    }

    private static String randomCredential() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record ControlRuntime(
            AnnotationConfigApplicationContext context,
            DefaultKafkaProducerFactory<String, String> producers,
            RuntimeTriggerService triggers,
            RuntimeSchedulingService scheduler,
            LeaseRecoveryService recoveries,
            OutboxPublicationService outbox,
            OutboxPublicationService.EventSink eventSink,
            RuntimeQueryService queries)
            implements AutoCloseable {
        @Override
        public void close() {
            context.close();
            producers.destroy();
        }
    }
}
