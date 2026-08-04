package com.workflowplatform.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ContractVersionTests {

    @Test
    void startsWithSchemaVersionOne() {
        assertEquals(
                1,
                ContractVersion.CURRENT_SCHEMA_VERSION,
                "The initial shared contract schema must remain version one");
    }
}
