package com.workflowplatform.controlplane.definition;

import com.workflowplatform.contracts.dsl.ParameterMapping;
import com.workflowplatform.contracts.dsl.WorkflowDefinition;
import com.workflowplatform.contracts.dsl.WorkflowEdge;
import com.workflowplatform.contracts.dsl.WorkflowNode;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.ConditionNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.DelayNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HttpNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.HumanTaskNodeConfiguration;
import com.workflowplatform.controlplane.definition.ValidatedWorkflowConfiguration.NodeConfiguration;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Deterministic compiler for the bounded CEL and named-root JSONPath profiles. */
@Component
@SuppressWarnings({
    "PMD.AssignmentInOperand",
    "PMD.AvoidFieldNameMatchingMethodName",
    "PMD.AvoidLiteralsInIfCondition",
    "PMD.PreserveStackTrace"
})
final class DeterministicWorkflowExpressionCompiler implements WorkflowExpressionCompiler {

    private static final Set<String> FORBIDDEN_IDENTIFIERS =
            Set.of("now", "random", "System", "Runtime", "class", "new", "import");
    private static final Set<String> ROOT_IDENTIFIERS =
            Set.of("input", "output", "response", "node", "trigger");
    private static final int MAXIMUM_STRING_LITERAL_LENGTH = 256;
    private static final int MAXIMUM_NUMBER_LITERAL_LENGTH = 64;

    private final ExpressionSandboxPolicy policy;

    DeterministicWorkflowExpressionCompiler() {
        this(ExpressionSandboxPolicy.DEFAULT);
    }

    DeterministicWorkflowExpressionCompiler(ExpressionSandboxPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public CompiledWorkflowExpressions compile(ValidatedWorkflowConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        WorkflowDefinition definition = configuration.graph().definition();
        Map<String, SchemaType> roots = roots(definition);
        Map<String, CompiledExpression> expressions = new LinkedHashMap<>();
        List<WorkflowExpressionViolation> violations = new ArrayList<>();
        compileNodes(configuration, roots, expressions, violations);
        compileEdges(definition, roots, expressions, violations);
        if (!violations.isEmpty()) {
            throw new WorkflowExpressionCompilationException(violations);
        }
        return new CompiledWorkflowExpressions(configuration, policy, expressions);
    }

    private void compileNodes(
            ValidatedWorkflowConfiguration configuration,
            Map<String, SchemaType> roots,
            Map<String, CompiledExpression> expressions,
            List<WorkflowExpressionViolation> violations) {
        List<WorkflowNode> nodes = configuration.graph().definition().nodes();
        Map<String, NodeConfiguration> typedNodes = configuration.nodes();
        for (int index = 0; index < nodes.size(); index++) {
            WorkflowNode node = nodes.get(index);
            NodeConfiguration typed = typedNodes.get(node.id());
            String base = "/nodes/" + index + "/config";
            if (typed instanceof ConditionNodeConfiguration condition) {
                compileCel(
                        condition.expression(),
                        ExpressionValueType.BOOLEAN,
                        base + "/expression",
                        roots,
                        expressions,
                        violations);
            } else if (typed instanceof HttpNodeConfiguration http) {
                compileHttp(http, base, roots, expressions, violations);
            } else if (typed instanceof DelayNodeConfiguration delay) {
                compileJsonPath(
                        delay.expiresAtExpression(),
                        ExpressionValueType.STRING,
                        base + "/expiresAtExpression",
                        roots,
                        expressions,
                        violations);
            } else if (typed instanceof HumanTaskNodeConfiguration human) {
                compileJsonPath(
                        human.assigneeExpression(),
                        ExpressionValueType.STRING,
                        base + "/assigneeExpression",
                        roots,
                        expressions,
                        violations);
            }
        }
    }

    private void compileHttp(
            HttpNodeConfiguration http,
            String base,
            Map<String, SchemaType> roots,
            Map<String, CompiledExpression> expressions,
            List<WorkflowExpressionViolation> violations) {
        for (Map.Entry<String, String> header : http.headers().entrySet()) {
            compileJsonPath(
                    Optional.of(header.getValue()),
                    ExpressionValueType.STRING,
                    base + "/headers/" + escapePointer(header.getKey()),
                    roots,
                    expressions,
                    violations);
        }
        compileJsonPath(
                http.bodyExpression(),
                ExpressionValueType.DYNAMIC,
                base + "/bodyExpression",
                roots,
                expressions,
                violations);
        compileJsonPath(
                http.resultExpression(),
                ExpressionValueType.DYNAMIC,
                base + "/resultExpression",
                roots,
                expressions,
                violations);
    }

    private void compileEdges(
            WorkflowDefinition definition,
            Map<String, SchemaType> roots,
            Map<String, CompiledExpression> expressions,
            List<WorkflowExpressionViolation> violations) {
        List<WorkflowEdge> edges = definition.edges();
        for (int index = 0; index < edges.size(); index++) {
            WorkflowEdge edge = edges.get(index);
            String base = "/edges/" + index;
            if (edge.when() != null) {
                compileCel(
                        edge.when(),
                        ExpressionValueType.BOOLEAN,
                        base + "/when",
                        roots,
                        expressions,
                        violations);
            }
            for (ParameterMapping mapping : edge.mapping()) {
                compileJsonPath(
                        Optional.of(mapping.expression()),
                        ExpressionValueType.DYNAMIC,
                        base + "/mapping/" + escapePointer(mapping.target()),
                        roots,
                        expressions,
                        violations);
            }
        }
    }

    private void compileCel(
            String source,
            ExpressionValueType expected,
            String path,
            Map<String, SchemaType> roots,
            Map<String, CompiledExpression> expressions,
            List<WorkflowExpressionViolation> violations) {
        try {
            ParsedExpression parsed = new CelParser(source, roots, policy).parse();
            requireAssignable(parsed.type(), expected);
            expressions.put(path, compiled(ExpressionLanguage.CEL, source, expected, parsed));
        } catch (CompilationFault fault) {
            violations.add(new WorkflowExpressionViolation(fault.failure(), path));
        }
    }

    private void compileJsonPath(
            Optional<String> source,
            ExpressionValueType expected,
            String path,
            Map<String, SchemaType> roots,
            Map<String, CompiledExpression> expressions,
            List<WorkflowExpressionViolation> violations) {
        if (source.isEmpty()) {
            return;
        }
        try {
            ParsedExpression parsed =
                    new NamedRootJsonPathParser(source.orElseThrow(), roots, policy).parse();
            requireAssignable(parsed.type(), expected);
            expressions.put(
                    path,
                    compiled(ExpressionLanguage.JSON_PATH, source.orElseThrow(), expected, parsed));
        } catch (CompilationFault fault) {
            violations.add(new WorkflowExpressionViolation(fault.failure(), path));
        }
    }

    private static CompiledExpression compiled(
            ExpressionLanguage language,
            String source,
            ExpressionValueType expected,
            ParsedExpression parsed) {
        return new CompiledExpression(
                language,
                parsed.type().valueType(),
                expected,
                sha256(source),
                parsed.tokenCount(),
                parsed.astNodes(),
                parsed.estimatedSteps());
    }

    private static Map<String, SchemaType> roots(WorkflowDefinition definition) {
        Map<String, SchemaType> result = new LinkedHashMap<>();
        result.put("input", SchemaType.fromJsonSchema(definition.parameters().inputSchema()));
        result.put("output", SchemaType.fromJsonSchema(definition.parameters().outputSchema()));
        result.put("response", SchemaType.DYNAMIC);
        result.put("node", SchemaType.DYNAMIC);
        result.put("trigger", SchemaType.DYNAMIC);
        return Map.copyOf(result);
    }

    private static void requireAssignable(SchemaType actual, ExpressionValueType expected) {
        ExpressionValueType actualType = actual.valueType();
        if (expected == ExpressionValueType.DYNAMIC
                || actualType == ExpressionValueType.DYNAMIC
                || actualType == expected
                || expected == ExpressionValueType.NUMBER
                        && actualType == ExpressionValueType.INTEGER) {
            return;
        }
        throw new CompilationFault(WorkflowExpressionCompilationFailure.TYPE_MISMATCH);
    }

    private static String sha256(String source) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String escapePointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private record ParsedExpression(
            SchemaType type, int tokenCount, int astNodes, int estimatedSteps) {}

    private record SchemaType(
            ExpressionValueType valueType,
            Map<String, SchemaType> properties,
            SchemaType elementType,
            boolean closedObject) {

        private static final SchemaType DYNAMIC =
                new SchemaType(ExpressionValueType.DYNAMIC, Map.of(), null, false);
        private static final SchemaType BOOLEAN = scalar(ExpressionValueType.BOOLEAN);
        private static final SchemaType INTEGER = scalar(ExpressionValueType.INTEGER);
        private static final SchemaType NUMBER = scalar(ExpressionValueType.NUMBER);
        private static final SchemaType STRING = scalar(ExpressionValueType.STRING);
        private static final SchemaType NULL = scalar(ExpressionValueType.NULL);

        private SchemaType {
            Objects.requireNonNull(valueType, "valueType");
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }

        private static SchemaType scalar(ExpressionValueType valueType) {
            return new SchemaType(valueType, Map.of(), null, false);
        }

        private static SchemaType fromJsonSchema(Map<String, Object> schema) {
            if (schema == null || schema.isEmpty()) {
                return DYNAMIC;
            }
            Object rawType = schema.get("type");
            if (!(rawType instanceof String type)) {
                return DYNAMIC;
            }
            return switch (type) {
                case "boolean" -> BOOLEAN;
                case "integer" -> INTEGER;
                case "number" -> NUMBER;
                case "string" -> STRING;
                case "null" -> NULL;
                case "array" -> array(schema);
                case "object" -> object(schema);
                default -> DYNAMIC;
            };
        }

        private static SchemaType array(Map<String, Object> schema) {
            Object items = schema.get("items");
            return new SchemaType(
                    ExpressionValueType.ARRAY,
                    Map.of(),
                    items instanceof Map<?, ?> map ? fromUnknownMap(map) : DYNAMIC,
                    false);
        }

        private static SchemaType object(Map<String, Object> schema) {
            Map<String, SchemaType> typedProperties = new LinkedHashMap<>();
            Object rawProperties = schema.get("properties");
            if (rawProperties instanceof Map<?, ?> properties) {
                for (Map.Entry<?, ?> entry : properties.entrySet()) {
                    if (entry.getKey() instanceof String key
                            && entry.getValue() instanceof Map<?, ?> property) {
                        typedProperties.put(key, fromUnknownMap(property));
                    }
                }
            }
            boolean closed = Boolean.FALSE.equals(schema.get("additionalProperties"));
            return new SchemaType(ExpressionValueType.OBJECT, typedProperties, null, closed);
        }

        private static SchemaType fromUnknownMap(Map<?, ?> source) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    typed.put(key, entry.getValue());
                }
            }
            return fromJsonSchema(typed);
        }

        private SchemaType field(String name) {
            if (valueType == ExpressionValueType.DYNAMIC) {
                return DYNAMIC;
            }
            if (valueType != ExpressionValueType.OBJECT) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.TYPE_MISMATCH);
            }
            SchemaType property = properties.get(name);
            if (property != null) {
                return property;
            }
            if (closedObject) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.IDENTIFIER_UNKNOWN);
            }
            return DYNAMIC;
        }

        private SchemaType index(int ignored) {
            if (valueType == ExpressionValueType.DYNAMIC) {
                return DYNAMIC;
            }
            if (valueType != ExpressionValueType.ARRAY) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.TYPE_MISMATCH);
            }
            return elementType == null ? DYNAMIC : elementType;
        }
    }

    private static final class CompilationFault extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final WorkflowExpressionCompilationFailure failure;

        private CompilationFault(WorkflowExpressionCompilationFailure failure) {
            super(failure.wireName());
            this.failure = failure;
        }

        private WorkflowExpressionCompilationFailure failure() {
            return failure;
        }
    }

    private enum TokenType {
        IDENTIFIER,
        INTEGER,
        NUMBER,
        STRING,
        TRUE,
        FALSE,
        NULL,
        LEFT_PAREN,
        RIGHT_PAREN,
        LEFT_BRACKET,
        RIGHT_BRACKET,
        DOT,
        NOT,
        MINUS,
        PLUS,
        MULTIPLY,
        DIVIDE,
        REMAINDER,
        EQUAL,
        NOT_EQUAL,
        LESS,
        LESS_EQUAL,
        GREATER,
        GREATER_EQUAL,
        AND,
        OR,
        END
    }

    private record Token(TokenType type, String text) {}

    private static final class CelLexer {

        private final String source;
        private final ExpressionSandboxPolicy policy;
        private final List<Token> tokens = new ArrayList<>();
        private int position;

        private CelLexer(String source, ExpressionSandboxPolicy policy) {
            this.source = Objects.requireNonNull(source, "source");
            this.policy = policy;
        }

        private List<Token> tokenize() {
            while (position < source.length()) {
                char current = source.charAt(position);
                if (Character.isWhitespace(current)) {
                    position++;
                } else if (isIdentifierStart(current)) {
                    identifier();
                } else if (Character.isDigit(current)) {
                    number();
                } else if (current == '\'' || current == '"') {
                    string();
                } else {
                    symbol();
                }
                if (tokens.size() > policy.maximumTokens()) {
                    throw new CompilationFault(
                            WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED);
                }
            }
            tokens.add(new Token(TokenType.END, ""));
            return List.copyOf(tokens);
        }

        private void identifier() {
            int start = position++;
            while (position < source.length() && isIdentifierPart(source.charAt(position))) {
                position++;
            }
            String value = source.substring(start, position);
            if (FORBIDDEN_IDENTIFIERS.contains(value)) {
                throw new CompilationFault(
                        WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
            }
            TokenType type =
                    switch (value) {
                        case "true" -> TokenType.TRUE;
                        case "false" -> TokenType.FALSE;
                        case "null" -> TokenType.NULL;
                        default -> TokenType.IDENTIFIER;
                    };
            tokens.add(new Token(type, value));
        }

        private void number() {
            int start = position;
            while (position < source.length() && Character.isDigit(source.charAt(position))) {
                position++;
            }
            boolean decimal = false;
            if (position < source.length() && source.charAt(position) == '.') {
                decimal = true;
                position++;
                int fraction = position;
                while (position < source.length() && Character.isDigit(source.charAt(position))) {
                    position++;
                }
                if (position == fraction) {
                    throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
                }
            }
            if (position < source.length()
                    && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
                decimal = true;
                position++;
                if (position < source.length()
                        && (source.charAt(position) == '+' || source.charAt(position) == '-')) {
                    position++;
                }
                int exponent = position;
                while (position < source.length() && Character.isDigit(source.charAt(position))) {
                    position++;
                }
                if (position == exponent) {
                    throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
                }
            }
            String value = source.substring(start, position);
            long digits = value.chars().filter(Character::isDigit).count();
            if (digits > MAXIMUM_NUMBER_LITERAL_LENGTH) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED);
            }
            try {
                new BigDecimal(value);
            } catch (NumberFormatException exception) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
            tokens.add(new Token(decimal ? TokenType.NUMBER : TokenType.INTEGER, value));
        }

        private void string() {
            char quote = source.charAt(position++);
            StringBuilder value = new StringBuilder();
            boolean terminated = false;
            while (position < source.length()) {
                char current = source.charAt(position++);
                if (current == quote) {
                    terminated = true;
                    break;
                }
                if (current == '\\') {
                    value.append(escape());
                } else if (current < 0x20) {
                    throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
                } else {
                    value.append(current);
                }
                if (value.length() > MAXIMUM_STRING_LITERAL_LENGTH) {
                    throw new CompilationFault(
                            WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED);
                }
            }
            if (!terminated) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
            tokens.add(new Token(TokenType.STRING, value.toString()));
        }

        private char escape() {
            if (position >= source.length()) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
            char escaped = source.charAt(position++);
            return switch (escaped) {
                case '\'', '"', '\\', '/' -> escaped;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> unicodeEscape();
                default ->
                        throw new CompilationFault(
                                WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            };
        }

        private char unicodeEscape() {
            if (position + 4 > source.length()) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
            String hex = source.substring(position, position + 4);
            position += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException exception) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
        }

        private void symbol() {
            char current = source.charAt(position++);
            switch (current) {
                case '(' -> add(TokenType.LEFT_PAREN, "(");
                case ')' -> add(TokenType.RIGHT_PAREN, ")");
                case '[' -> add(TokenType.LEFT_BRACKET, "[");
                case ']' -> add(TokenType.RIGHT_BRACKET, "]");
                case '.' -> add(TokenType.DOT, ".");
                case '+' -> add(TokenType.PLUS, "+");
                case '-' -> add(TokenType.MINUS, "-");
                case '*' -> add(TokenType.MULTIPLY, "*");
                case '/' -> add(TokenType.DIVIDE, "/");
                case '%' -> add(TokenType.REMAINDER, "%");
                case '!' -> add(match('=') ? TokenType.NOT_EQUAL : TokenType.NOT, "!");
                case '=' -> requiredPair('=', TokenType.EQUAL);
                case '<' -> add(match('=') ? TokenType.LESS_EQUAL : TokenType.LESS, "<");
                case '>' -> add(match('=') ? TokenType.GREATER_EQUAL : TokenType.GREATER, ">");
                case '&' -> requiredPair('&', TokenType.AND);
                case '|' -> requiredPair('|', TokenType.OR);
                default ->
                        throw new CompilationFault(
                                WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
            }
        }

        private void requiredPair(char expected, TokenType type) {
            if (!match(expected)) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
            add(type, Character.toString(expected));
        }

        private boolean match(char expected) {
            if (position >= source.length() || source.charAt(position) != expected) {
                return false;
            }
            position++;
            return true;
        }

        private void add(TokenType type, String text) {
            tokens.add(new Token(type, text));
        }

        private static boolean isIdentifierStart(char value) {
            return value == '_' || Character.isLetter(value);
        }

        private static boolean isIdentifierPart(char value) {
            return value == '_' || Character.isLetterOrDigit(value);
        }
    }

    private static final class CelParser {

        private final List<Token> tokens;
        private final Map<String, SchemaType> roots;
        private final ExpressionSandboxPolicy policy;
        private int position;
        private int astNodes;
        private int nesting;

        private CelParser(
                String source, Map<String, SchemaType> roots, ExpressionSandboxPolicy policy) {
            if (source == null || source.isBlank()) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
            this.tokens = new CelLexer(source, policy).tokenize();
            this.roots = roots;
            this.policy = policy;
        }

        private ParsedExpression parse() {
            SchemaType type = expression();
            expect(TokenType.END);
            int steps = Math.multiplyExact(astNodes, 2);
            if (steps > policy.maximumEvaluationSteps()) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED);
            }
            return new ParsedExpression(type, tokens.size() - 1, astNodes, steps);
        }

        private SchemaType expression() {
            return or();
        }

        private SchemaType or() {
            SchemaType result = and();
            while (match(TokenType.OR)) {
                requireBoolean(result);
                requireBoolean(and());
                result = SchemaType.BOOLEAN;
                node();
            }
            return result;
        }

        private SchemaType and() {
            SchemaType result = equality();
            while (match(TokenType.AND)) {
                requireBoolean(result);
                requireBoolean(equality());
                result = SchemaType.BOOLEAN;
                node();
            }
            return result;
        }

        private SchemaType equality() {
            SchemaType result = comparison();
            while (match(TokenType.EQUAL, TokenType.NOT_EQUAL)) {
                SchemaType right = comparison();
                requireEquality(result, right);
                result = SchemaType.BOOLEAN;
                node();
            }
            return result;
        }

        private SchemaType comparison() {
            SchemaType result = addition();
            while (match(
                    TokenType.LESS,
                    TokenType.LESS_EQUAL,
                    TokenType.GREATER,
                    TokenType.GREATER_EQUAL)) {
                SchemaType right = addition();
                requireComparable(result, right);
                result = SchemaType.BOOLEAN;
                node();
            }
            return result;
        }

        private SchemaType addition() {
            SchemaType result = multiplication();
            while (match(TokenType.PLUS, TokenType.MINUS)) {
                TokenType operator = previous().type();
                SchemaType right = multiplication();
                result = arithmetic(result, right, operator == TokenType.PLUS);
                node();
            }
            return result;
        }

        private SchemaType multiplication() {
            SchemaType result = unary();
            while (match(TokenType.MULTIPLY, TokenType.DIVIDE, TokenType.REMAINDER)) {
                SchemaType right = unary();
                result = arithmetic(result, right, false);
                node();
            }
            return result;
        }

        private SchemaType unary() {
            if (match(TokenType.NOT)) {
                enterNesting();
                SchemaType type = unary();
                leaveNesting();
                requireBoolean(type);
                node();
                return SchemaType.BOOLEAN;
            }
            if (match(TokenType.MINUS)) {
                enterNesting();
                SchemaType type = unary();
                leaveNesting();
                requireNumeric(type);
                node();
                return type;
            }
            return primary();
        }

        private SchemaType primary() {
            if (peek().type() == TokenType.LEFT_BRACKET) {
                throw new CompilationFault(
                        WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
            }
            if (match(TokenType.TRUE, TokenType.FALSE)) {
                node();
                return SchemaType.BOOLEAN;
            }
            if (match(TokenType.INTEGER)) {
                node();
                return SchemaType.INTEGER;
            }
            if (match(TokenType.NUMBER)) {
                node();
                return SchemaType.NUMBER;
            }
            if (match(TokenType.STRING)) {
                node();
                return SchemaType.STRING;
            }
            if (match(TokenType.NULL)) {
                node();
                return SchemaType.NULL;
            }
            if (match(TokenType.LEFT_PAREN)) {
                enterNesting();
                SchemaType type = expression();
                expect(TokenType.RIGHT_PAREN);
                leaveNesting();
                return type;
            }
            if (match(TokenType.IDENTIFIER)) {
                return path(previous().text());
            }
            throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
        }

        private SchemaType path(String rootName) {
            if (peek().type() == TokenType.LEFT_PAREN) {
                throw new CompilationFault(
                        WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
            }
            SchemaType type = roots.get(rootName);
            if (type == null || !ROOT_IDENTIFIERS.contains(rootName)) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.IDENTIFIER_UNKNOWN);
            }
            node();
            int segments = 0;
            while (true) {
                if (match(TokenType.DOT)) {
                    Token field = expect(TokenType.IDENTIFIER);
                    if (peek().type() == TokenType.LEFT_PAREN) {
                        throw new CompilationFault(
                                WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
                    }
                    type = type.field(field.text());
                    segments++;
                    node();
                } else if (match(TokenType.LEFT_BRACKET)) {
                    if (match(TokenType.STRING)) {
                        type = type.field(previous().text());
                    } else if (match(TokenType.INTEGER)) {
                        type = type.index(parseIndex(previous().text()));
                    } else {
                        throw new CompilationFault(
                                WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
                    }
                    expect(TokenType.RIGHT_BRACKET);
                    segments++;
                    node();
                } else if (peek().type() == TokenType.LEFT_PAREN) {
                    throw new CompilationFault(
                            WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
                } else {
                    break;
                }
                if (segments > policy.maximumPathSegments()) {
                    throw new CompilationFault(
                            WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED);
                }
            }
            return type;
        }

        private void enterNesting() {
            nesting++;
            if (nesting > policy.maximumNesting()) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED);
            }
        }

        private void leaveNesting() {
            nesting--;
        }

        private void node() {
            astNodes++;
            if (astNodes > policy.maximumAstNodes()) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED);
            }
        }

        private boolean match(TokenType... types) {
            for (TokenType type : types) {
                if (peek().type() == type) {
                    position++;
                    return true;
                }
            }
            return false;
        }

        private Token expect(TokenType type) {
            if (peek().type() != type) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
            return tokens.get(position++);
        }

        private Token peek() {
            return tokens.get(position);
        }

        private Token previous() {
            return tokens.get(position - 1);
        }
    }

    private static final class NamedRootJsonPathParser {

        private final String source;
        private final Map<String, SchemaType> roots;
        private final ExpressionSandboxPolicy policy;
        private int position;
        private int segments;

        private NamedRootJsonPathParser(
                String source, Map<String, SchemaType> roots, ExpressionSandboxPolicy policy) {
            this.source = Objects.requireNonNull(source, "source");
            this.roots = roots;
            this.policy = policy;
        }

        private ParsedExpression parse() {
            if (source.isBlank() || source.chars().anyMatch(Character::isWhitespace)) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
            if (source.charAt(0) == '$') {
                throw new CompilationFault(
                        WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
            }
            String root = identifier();
            if (!ROOT_IDENTIFIERS.contains(root) || !roots.containsKey(root)) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.IDENTIFIER_UNKNOWN);
            }
            SchemaType type = roots.get(root);
            while (position < source.length()) {
                char current = source.charAt(position);
                if (current == '.') {
                    position++;
                    if (position < source.length() && source.charAt(position) == '.') {
                        throw new CompilationFault(
                                WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
                    }
                    if (position < source.length() && source.charAt(position) == '*') {
                        throw new CompilationFault(
                                WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
                    }
                    type = type.field(identifier());
                } else if (current == '[') {
                    type = bracket(type);
                } else {
                    throw new CompilationFault(
                            WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
                }
                segments++;
                if (segments > policy.maximumPathSegments()) {
                    throw new CompilationFault(
                            WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED);
                }
            }
            int nodes = 1 + segments;
            int steps = nodes * 2;
            if (nodes > policy.maximumAstNodes() || steps > policy.maximumEvaluationSteps()) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED);
            }
            return new ParsedExpression(type, nodes, nodes, steps);
        }

        private SchemaType bracket(SchemaType type) {
            position++;
            if (position >= source.length()) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
            char current = source.charAt(position);
            if (current == '\'' || current == '"') {
                String key = quotedKey(current);
                require(']');
                return type.field(key);
            }
            int start = position;
            while (position < source.length() && Character.isDigit(source.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw new CompilationFault(
                        WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
            }
            int index = parseIndex(source.substring(start, position));
            if (position < source.length()
                    && (source.charAt(position) == ',' || source.charAt(position) == ':')) {
                throw new CompilationFault(
                        WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
            }
            require(']');
            return type.index(index);
        }

        private String quotedKey(char quote) {
            position++;
            StringBuilder result = new StringBuilder();
            boolean terminated = false;
            while (position < source.length()) {
                char current = source.charAt(position++);
                if (current == quote) {
                    terminated = true;
                    break;
                }
                if (current == '\\') {
                    if (position >= source.length()) {
                        break;
                    }
                    char escaped = source.charAt(position++);
                    if (escaped != quote && escaped != '\\') {
                        throw new CompilationFault(
                                WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
                    }
                    result.append(escaped);
                } else if (current < 0x20) {
                    throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
                } else {
                    result.append(current);
                }
                if (result.length() > MAXIMUM_STRING_LITERAL_LENGTH) {
                    throw new CompilationFault(
                            WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED);
                }
            }
            if (!terminated || result.isEmpty()) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
            return result.toString();
        }

        private String identifier() {
            if (position >= source.length()
                    || !(source.charAt(position) == '_'
                            || Character.isLetter(source.charAt(position)))) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
            int start = position++;
            while (position < source.length()) {
                char current = source.charAt(position);
                if (current != '_' && !Character.isLetterOrDigit(current)) {
                    break;
                }
                position++;
            }
            String value = source.substring(start, position);
            if (FORBIDDEN_IDENTIFIERS.contains(value)) {
                throw new CompilationFault(
                        WorkflowExpressionCompilationFailure.OPERATION_FORBIDDEN);
            }
            return value;
        }

        private void require(char expected) {
            if (position >= source.length() || source.charAt(position) != expected) {
                throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
            }
            position++;
        }
    }

    private static int parseIndex(String value) {
        if (value.length() > 9) {
            throw new CompilationFault(WorkflowExpressionCompilationFailure.BUDGET_EXCEEDED);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new CompilationFault(WorkflowExpressionCompilationFailure.SYNTAX_INVALID);
        }
    }

    private static SchemaType arithmetic(
            SchemaType left, SchemaType right, boolean allowStringConcatenation) {
        ExpressionValueType leftType = left.valueType();
        ExpressionValueType rightType = right.valueType();
        if (leftType == ExpressionValueType.DYNAMIC || rightType == ExpressionValueType.DYNAMIC) {
            return SchemaType.DYNAMIC;
        }
        if (allowStringConcatenation
                && leftType == ExpressionValueType.STRING
                && rightType == ExpressionValueType.STRING) {
            return SchemaType.STRING;
        }
        requireNumeric(left);
        requireNumeric(right);
        return leftType == ExpressionValueType.NUMBER || rightType == ExpressionValueType.NUMBER
                ? SchemaType.NUMBER
                : SchemaType.INTEGER;
    }

    private static void requireBoolean(SchemaType type) {
        if (type.valueType() != ExpressionValueType.BOOLEAN
                && type.valueType() != ExpressionValueType.DYNAMIC) {
            throw new CompilationFault(WorkflowExpressionCompilationFailure.TYPE_MISMATCH);
        }
    }

    private static void requireNumeric(SchemaType type) {
        if (type.valueType() != ExpressionValueType.INTEGER
                && type.valueType() != ExpressionValueType.NUMBER
                && type.valueType() != ExpressionValueType.DYNAMIC) {
            throw new CompilationFault(WorkflowExpressionCompilationFailure.TYPE_MISMATCH);
        }
    }

    private static void requireComparable(SchemaType left, SchemaType right) {
        ExpressionValueType leftType = left.valueType();
        ExpressionValueType rightType = right.valueType();
        if (leftType == ExpressionValueType.DYNAMIC
                || rightType == ExpressionValueType.DYNAMIC
                || leftType == ExpressionValueType.STRING && rightType == ExpressionValueType.STRING
                || isNumeric(leftType) && isNumeric(rightType)) {
            return;
        }
        throw new CompilationFault(WorkflowExpressionCompilationFailure.TYPE_MISMATCH);
    }

    private static void requireEquality(SchemaType left, SchemaType right) {
        ExpressionValueType leftType = left.valueType();
        ExpressionValueType rightType = right.valueType();
        if (leftType == ExpressionValueType.DYNAMIC
                || rightType == ExpressionValueType.DYNAMIC
                || leftType == ExpressionValueType.NULL
                || rightType == ExpressionValueType.NULL
                || leftType == rightType
                || isNumeric(leftType) && isNumeric(rightType)) {
            return;
        }
        throw new CompilationFault(WorkflowExpressionCompilationFailure.TYPE_MISMATCH);
    }

    private static boolean isNumeric(ExpressionValueType type) {
        return type == ExpressionValueType.INTEGER || type == ExpressionValueType.NUMBER;
    }
}
