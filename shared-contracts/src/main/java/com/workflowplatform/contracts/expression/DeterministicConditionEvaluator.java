package com.workflowplatform.contracts.expression;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Resource-bounded evaluator for the deterministic CEL subset accepted by the DSL. */
public final class DeterministicConditionEvaluator {

    private static final int MAXIMUM_TOKENS = 256;
    private static final int MAXIMUM_DEPTH = 32;
    private static final char ESCAPE = '\\';

    public boolean evaluate(String expression, JsonNode input) {
        if (expression == null || expression.isBlank() || expression.length() > 4096) {
            throw new EvaluationException("condition expression is missing or too large");
        }
        if (input == null) {
            throw new EvaluationException("condition input is required");
        }
        Parser parser = new Parser(tokenize(expression), input);
        Object result = parser.parse();
        if (!(result instanceof Boolean booleanResult)) {
            throw new EvaluationException("condition expression did not return boolean");
        }
        return booleanResult;
    }

    private static List<Token> tokenize(String expression) {
        List<Token> tokens = new ArrayList<>();
        int cursor = 0;
        while (cursor < expression.length()) {
            char current = expression.charAt(cursor);
            if (Character.isWhitespace(current)) {
                cursor++;
                continue;
            }
            if (cursor + 1 < expression.length()) {
                String pair = expression.substring(cursor, cursor + 2);
                TokenType type =
                        switch (pair) {
                            case "&&" -> TokenType.AND;
                            case "||" -> TokenType.OR;
                            case "==" -> TokenType.EQUAL;
                            case "!=" -> TokenType.NOT_EQUAL;
                            case "<=" -> TokenType.LESS_EQUAL;
                            case ">=" -> TokenType.GREATER_EQUAL;
                            default -> null;
                        };
                if (type != null) {
                    tokens.add(new Token(type, pair));
                    cursor += 2;
                    requireBudget(tokens);
                    continue;
                }
            }
            TokenType single =
                    switch (current) {
                        case '!' -> TokenType.NOT;
                        case '<' -> TokenType.LESS;
                        case '>' -> TokenType.GREATER;
                        case '(' -> TokenType.LEFT_PARENTHESIS;
                        case ')' -> TokenType.RIGHT_PARENTHESIS;
                        default -> null;
                    };
            if (single != null) {
                tokens.add(new Token(single, Character.toString(current)));
                cursor++;
                requireBudget(tokens);
                continue;
            }
            if (current == '\'' || current == '"') {
                cursor = tokenizeString(expression, cursor, current, tokens);
                requireBudget(tokens);
                continue;
            }
            if (Character.isDigit(current) || current == '-') {
                int start = cursor;
                cursor++;
                while (cursor < expression.length()) {
                    char character = expression.charAt(cursor);
                    if (!Character.isDigit(character)
                            && character != '.'
                            && character != 'e'
                            && character != 'E'
                            && character != '+'
                            && character != '-') {
                        break;
                    }
                    cursor++;
                }
                String number = expression.substring(start, cursor);
                try {
                    number = decimal(number).toString();
                } catch (NumberFormatException exception) {
                    throw new EvaluationException(
                            "condition numeric literal is invalid", exception);
                }
                tokens.add(new Token(TokenType.NUMBER, number));
                requireBudget(tokens);
                continue;
            }
            if (Character.isLetter(current) || current == '_') {
                int start = cursor;
                cursor++;
                while (cursor < expression.length()) {
                    char character = expression.charAt(cursor);
                    if (!Character.isLetterOrDigit(character)
                            && character != '_'
                            && character != '.') {
                        break;
                    }
                    cursor++;
                }
                String identifier = expression.substring(start, cursor);
                TokenType keyword =
                        switch (identifier) {
                            case "true" -> TokenType.TRUE;
                            case "false" -> TokenType.FALSE;
                            case "null" -> TokenType.NULL;
                            default -> TokenType.IDENTIFIER;
                        };
                tokens.add(new Token(keyword, identifier));
                requireBudget(tokens);
                continue;
            }
            throw new EvaluationException("condition expression contains an unsupported token");
        }
        tokens.add(new Token(TokenType.END, ""));
        return List.copyOf(tokens);
    }

    private static int tokenizeString(
            String expression, int initialCursor, char quote, List<Token> tokens) {
        int start = initialCursor;
        int cursor = initialCursor + 1;
        StringBuilder value = new StringBuilder();
        boolean closed = false;
        while (cursor < expression.length()) {
            char character = expression.charAt(cursor);
            cursor++;
            if (character == quote) {
                closed = true;
                break;
            }
            if (character == ESCAPE) {
                if (cursor >= expression.length()) {
                    break;
                }
                char escaped = expression.charAt(cursor);
                cursor++;
                if (escaped != quote && escaped != ESCAPE) {
                    throw new EvaluationException("condition string escape is unsupported");
                }
                character = escaped;
            }
            if (Character.isISOControl(character) || value.length() >= 1000) {
                throw new EvaluationException("condition string literal is invalid");
            }
            value.append(character);
        }
        if (!closed) {
            throw new EvaluationException("condition string literal is not closed at " + start);
        }
        tokens.add(new Token(TokenType.STRING, value.toString()));
        return cursor;
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static void requireBudget(List<Token> tokens) {
        if (tokens.size() > MAXIMUM_TOKENS) {
            throw new EvaluationException("condition token budget exceeded");
        }
    }

    private enum TokenType {
        AND,
        OR,
        EQUAL,
        NOT_EQUAL,
        LESS,
        LESS_EQUAL,
        GREATER,
        GREATER_EQUAL,
        NOT,
        LEFT_PARENTHESIS,
        RIGHT_PARENTHESIS,
        IDENTIFIER,
        STRING,
        NUMBER,
        TRUE,
        FALSE,
        NULL,
        END
    }

    private record Token(TokenType type, String value) {}

    private static final class Parser {
        private final List<Token> tokens;
        private final JsonNode input;
        private int cursor;
        private int depth;

        Parser(List<Token> expressionTokens, JsonNode inputValue) {
            tokens = expressionTokens;
            input = inputValue;
        }

        Object parse() {
            Object value = parseOr();
            require(TokenType.END);
            return value;
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (match(TokenType.OR)) {
                Object right = parseAnd();
                left = requireBoolean(left) || requireBoolean(right);
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseEquality();
            while (match(TokenType.AND)) {
                Object right = parseEquality();
                left = requireBoolean(left) && requireBoolean(right);
            }
            return left;
        }

        private Object parseEquality() {
            Object left = parseRelational();
            while (peek(TokenType.EQUAL) || peek(TokenType.NOT_EQUAL)) {
                TokenType operator = advance().type();
                Object right = parseRelational();
                boolean equal = valuesEqual(left, right);
                left = operator == TokenType.EQUAL ? equal : !equal;
            }
            return left;
        }

        private Object parseRelational() {
            Object left = parseUnary();
            while (peek(TokenType.LESS)
                    || peek(TokenType.LESS_EQUAL)
                    || peek(TokenType.GREATER)
                    || peek(TokenType.GREATER_EQUAL)) {
                TokenType operator = advance().type();
                Object right = parseUnary();
                int comparison = compare(left, right);
                left =
                        switch (operator) {
                            case LESS -> comparison < 0;
                            case LESS_EQUAL -> comparison <= 0;
                            case GREATER -> comparison > 0;
                            case GREATER_EQUAL -> comparison >= 0;
                            default -> throw new EvaluationException("invalid condition operator");
                        };
            }
            return left;
        }

        private Object parseUnary() {
            if (match(TokenType.NOT)) {
                return !requireBoolean(parseUnary());
            }
            return parsePrimary();
        }

        private Object parsePrimary() {
            Token token = advance();
            return switch (token.type()) {
                case TRUE -> true;
                case FALSE -> false;
                case NULL -> null;
                case STRING -> token.value();
                case NUMBER -> new BigDecimal(token.value());
                case IDENTIFIER -> resolve(token.value());
                case LEFT_PARENTHESIS -> parenthesized();
                default -> throw new EvaluationException("condition value was expected");
            };
        }

        private Object parenthesized() {
            depth++;
            if (depth > MAXIMUM_DEPTH) {
                throw new EvaluationException("condition nesting budget exceeded");
            }
            Object value = parseOr();
            require(TokenType.RIGHT_PARENTHESIS);
            depth--;
            return value;
        }

        private Object resolve(String identifier) {
            String[] segments = identifier.split("\\.", -1);
            if (segments.length < 2 || !"input".equals(segments[0]) || segments.length > 17) {
                throw new EvaluationException("condition identifier is outside the input root");
            }
            JsonNode value = input;
            for (int index = 1; index < segments.length; index++) {
                if (!segments[index].matches("[A-Za-z_][A-Za-z0-9_]{0,79}")) {
                    throw new EvaluationException("condition identifier segment is invalid");
                }
                value = value.path(segments[index]);
            }
            if (value.isMissingNode() || value.isContainerNode()) {
                throw new EvaluationException("condition identifier is missing or not scalar");
            }
            if (value.isNull()) {
                return null;
            }
            if (value.isBoolean()) {
                return value.booleanValue();
            }
            if (value.isNumber()) {
                return value.decimalValue();
            }
            if (value.isTextual()) {
                return value.textValue();
            }
            throw new EvaluationException("condition identifier type is unsupported");
        }

        private boolean match(TokenType type) {
            if (!peek(type)) {
                return false;
            }
            cursor++;
            return true;
        }

        private boolean peek(TokenType type) {
            return tokens.get(cursor).type() == type;
        }

        private Token advance() {
            Token token = tokens.get(cursor);
            cursor++;
            return token;
        }

        private void require(TokenType type) {
            if (!match(type)) {
                throw new EvaluationException("condition expression is incomplete");
            }
        }

        private static boolean requireBoolean(Object value) {
            if (!(value instanceof Boolean booleanValue)) {
                throw new EvaluationException("condition boolean operand was expected");
            }
            return booleanValue;
        }

        private static boolean valuesEqual(Object left, Object right) {
            if (left instanceof BigDecimal leftNumber && right instanceof BigDecimal rightNumber) {
                return leftNumber.compareTo(rightNumber) == 0;
            }
            return Objects.equals(left, right);
        }

        private static int compare(Object left, Object right) {
            if (left instanceof BigDecimal leftNumber && right instanceof BigDecimal rightNumber) {
                return leftNumber.compareTo(rightNumber);
            }
            if (left instanceof String leftText && right instanceof String rightText) {
                return leftText.compareTo(rightText);
            }
            throw new EvaluationException("condition comparison operands have incompatible types");
        }
    }

    public static final class EvaluationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EvaluationException(String message) {
            super(message);
        }

        EvaluationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
