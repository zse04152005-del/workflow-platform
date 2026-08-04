package com.workflowplatform.controlplane.runtime.internal;

import com.workflowplatform.payload.MinioTenantPayloadStore;
import com.workflowplatform.payload.TenantPayloadStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime object-storage wiring; credentials remain external configuration. */
@Configuration
class RuntimePayloadStorageConfiguration {

    @Bean
    TenantPayloadStore runtimeTenantPayloadStore(
            @Value("${workflow.object-storage.endpoint:http://127.0.0.1:19000}") String endpoint,
            @Value("${workflow.object-storage.access-key}") String accessKey,
            @Value("${workflow.object-storage.secret-key}") String secretKey,
            @Value("${workflow.object-storage.bucket:workflow-payloads}") String bucket) {
        return new MinioTenantPayloadStore(endpoint, accessKey, secretKey, bucket);
    }
}
