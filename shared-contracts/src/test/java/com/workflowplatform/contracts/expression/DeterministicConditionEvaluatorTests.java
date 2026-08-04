package com.workflowplatform.contracts.expression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class DeterministicConditionEvaluatorTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DeterministicConditionEvaluator evaluator = new DeterministicConditionEvaluator();

    @Test
    void evaluatesBooleanNumericStringNullAndPrecedenceOperations() throws Exception {
        JsonNode input =
                mapper.readTree(
                        """
                        {
                          "amount": 100,
                          "enabled": true,
                          "name": "order",
                          "optional": null,
                          "nested": {"score": 2.0}
                        }
                        """);

        assertTrue(evaluator.evaluate("input.amount <= 500 && input.enabled", input));
        assertTrue(evaluator.evaluate("input.name == 'order'", input));
        assertTrue(evaluator.evaluate("input.optional == null", input));
        assertTrue(evaluator.evaluate("input.nested.score == 2", input));
        assertTrue(evaluator.evaluate("!(input.amount > 500) && (false || true)", input));
        assertTrue(evaluator.evaluate("input.name >= 'alpha' && input.name != 'other'", input));
        assertFalse(evaluator.evaluate("input.amount < 100 || input.enabled == false", input));
    }

    @Test
    void acceptsBoundedQuotedEscapesAndScientificNumbers() throws Exception {
        JsonNode input = mapper.readTree("{\"text\":\"a'b\",\"amount\":100}");

        assertTrue(evaluator.evaluate("input.text == 'a\\'b'", input));
        assertTrue(evaluator.evaluate("input.amount == 1e2", input));
    }

    @Test
    void rejectsNonBooleanMissingContainerAndIncompatibleValues() throws Exception {
        JsonNode input = mapper.readTree("{\"amount\":1,\"object\":{\"value\":1}}");

        assertThrows(
                DeterministicConditionEvaluator.EvaluationException.class,
                () -> evaluator.evaluate("input.amount", input));
        assertThrows(
                DeterministicConditionEvaluator.EvaluationException.class,
                () -> evaluator.evaluate("input.missing == 1", input));
        assertThrows(
                DeterministicConditionEvaluator.EvaluationException.class,
                () -> evaluator.evaluate("input.object == null", input));
        assertThrows(
                DeterministicConditionEvaluator.EvaluationException.class,
                () -> evaluator.evaluate("input.amount < 'two'", input));
        assertThrows(
                DeterministicConditionEvaluator.EvaluationException.class,
                () -> evaluator.evaluate("input.amount && true", input));
    }

    @Test
    void rejectsUnsupportedMalformedAndUnboundedExpressions() throws Exception {
        JsonNode input = mapper.readTree("{\"amount\":1}");
        String tooDeep = "(".repeat(33) + "true" + ")".repeat(33);
        String tooManyTokens = "true && ".repeat(257) + "true";

        for (String expression :
                new String[] {
                    "",
                    "size(input) > 0",
                    "input.amount == 1e",
                    "input.amount == @",
                    "input.amount == 'unterminated",
                    "input.amount == 'bad\\nescape'",
                    "input == 1",
                    "input.bad-segment == 1",
                    "true false",
                    tooDeep,
                    tooManyTokens
                }) {
            assertThrows(
                    DeterministicConditionEvaluator.EvaluationException.class,
                    () -> evaluator.evaluate(expression, input));
        }
        assertThrows(
                DeterministicConditionEvaluator.EvaluationException.class,
                () -> evaluator.evaluate("true", null));
        assertThrows(
                DeterministicConditionEvaluator.EvaluationException.class,
                () -> evaluator.evaluate("x".repeat(4097), input));
    }
}
