package org.me.advancedsky.messaging;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.IslandNotHostedHereException;

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
        assertThrows(IllegalArgumentException.class, () -> CrossServerMessenger.restoreRemoteException("unknown.Exception", "failure"));
        assertThrows(IllegalArgumentException.class, () -> CrossServerMessenger.restoreRemoteException(null, "failure"));
        assertThrows(IllegalArgumentException.class, () -> CrossServerMessenger.restoreRemoteException("", "failure"));
        assertThrows(IllegalArgumentException.class, () -> CrossServerMessenger.restoreRemoteException(Exception.class.getName(), "failure"));
        assertThrows(IllegalArgumentException.class, () -> CrossServerMessenger.restoreRemoteException(java.util.concurrent.CompletionException.class.getName(), "failure"));
    }

    @Test
    void remoteFailuresKeepTheirTypesAndMessageData() {
        assertInstanceOf(IslandDoesNotExistException.class,
                CrossServerMessenger.restoreRemoteException(IslandDoesNotExistException.class.getName(), null));
        IslandNotHostedHereException hostError = assertInstanceOf(IslandNotHostedHereException.class,
                CrossServerMessenger.restoreRemoteException(IslandNotHostedHereException.class.getName(), "sky2"));
        assertEquals("sky2", hostError.getServer());
        RuntimeException runtimeError = CrossServerMessenger.restoreRemoteException(IllegalStateException.class.getName(), "database unavailable");
        assertInstanceOf(IllegalStateException.class, runtimeError);
        assertEquals("database unavailable", runtimeError.getMessage());
    }

    @Test
    void aNewExceptionNeedsNoSeparateRegistryEntry() {
        RuntimeException error = CrossServerMessenger.restoreRemoteException(NewOperationException.class.getName(), "operation failed");
        assertInstanceOf(NewOperationException.class, error);
        assertEquals("operation failed", error.getMessage());
    }

    public static class NewOperationException extends RuntimeException {
        public NewOperationException(String message) {
            super(message);
        }
    }

    @Test
    void aMissingPayloadCannotBecomeAnEmptyRequest() {
        assertThrows(NullPointerException.class, () -> CrossServerMessage.request("sky1", "sky2", "island.create", null));
    }
}
