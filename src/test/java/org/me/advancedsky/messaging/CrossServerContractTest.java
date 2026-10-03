package org.me.advancedsky.messaging;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrossServerContractTest {
    @Test
    void theCurrentRequestRoundTripsWithAllFieldsExplicit() {
        CrossServerMessage request = CrossServerMessage.request("sky1", "sky2", "island.create", new JSONObject());
        JSONObject encoded = new JSONObject(request.toJson());
        assertTrue(encoded.has("status"));
        assertSame(JSONObject.NULL, encoded.get("status"));
        assertEquals(request.getMessageId(), CrossServerMessage.fromJson(encoded.toString()).getCorrelationId());
    }

    @Test
    void missingRequiredFieldsAreRejectedInsteadOfInferred() {
        JSONObject encoded = new JSONObject(CrossServerMessage.request("sky1", "sky2", "island.create", new JSONObject()).toJson());
        for (String field : new String[]{"version", "correlationId", "payload", "status", "errorType", "errorMessage"}) {
            JSONObject invalid = new JSONObject(encoded.toString());
            invalid.remove(field);
            assertThrows(RuntimeException.class, () -> CrossServerMessage.fromJson(invalid.toString()), field);
        }
        assertThrows(IllegalArgumentException.class, () -> CrossServerMessage.fromJson(encoded.put("version", 2).toString()));
    }

    @Test
    void unsupportedErrorTypesAreRejectedInsteadOfReplacingTheirType() {
        assertThrows(IllegalArgumentException.class, () -> RemoteErrors.decode("unknown.Exception", "failure"));
        assertThrows(IllegalArgumentException.class, () -> RemoteErrors.decode(null, "failure"));
    }

    @Test
    void aMissingPayloadCannotBecomeAnEmptyRequest() {
        assertThrows(NullPointerException.class, () -> CrossServerMessage.request("sky1", "sky2", "island.create", null));
    }
}
