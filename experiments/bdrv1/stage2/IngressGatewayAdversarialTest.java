package dev.bat.examples.ingress;

import java.util.List;

/** Hidden evaluator input. Never mount this file into a candidate session. */
public final class IngressGatewayAdversarialTest {
    private static final List<String> SENDERS = List.of(
            "", "@corp.test", "a@corp.test", "a@example.test", "a@corp.test.attacker");

    public static void main(String[] args) {
        for (String sender : SENDERS) {
            assertInternal(sender, true);
            assertInternal(sender, false);
            assertExternal(sender, true);
            assertExternal(sender, false);
        }
        preservesExternalBody();
        internalNeverReachesThrowingScanner();
        externalReachesThrowingScanner();
        System.out.println("PASS IngressGatewayAdversarialTest");
    }

    private static void assertInternal(String sender, boolean allows) {
        RecordingScanner scanner = new RecordingScanner(allows);
        Decision actual = new IngressGateway(scanner).acceptInternal(sender, "body");
        assertEquals(Decision.ACCEPTED, actual, "internal decision: " + sender + "/" + allows);
        assertEquals(0, scanner.calls, "internal calls: " + sender + "/" + allows);
    }

    private static void assertExternal(String sender, boolean allows) {
        RecordingScanner scanner = new RecordingScanner(allows);
        Decision actual = new IngressGateway(scanner).acceptExternal(sender, "body");
        assertEquals(allows ? Decision.ACCEPTED : Decision.REJECTED, actual,
                "external decision: " + sender + "/" + allows);
        assertEquals(1, scanner.calls, "external calls: " + sender + "/" + allows);
    }

    private static void preservesExternalBody() {
        RecordingScanner scanner = new RecordingScanner(true);
        String body = "body-with-identity-\u2603";
        new IngressGateway(scanner).acceptExternal("sender", body);
        assertEquals(body, scanner.lastBody, "external body");
    }

    private static void internalNeverReachesThrowingScanner() {
        ContentScanner throwsIfCalled = body -> {
            throw new ProbeException();
        };
        Decision actual = new IngressGateway(throwsIfCalled).acceptInternal("", "body");
        assertEquals(Decision.ACCEPTED, actual, "internal throwing scanner");
    }

    private static void externalReachesThrowingScanner() {
        ContentScanner throwsIfCalled = body -> {
            throw new ProbeException();
        };
        try {
            new IngressGateway(throwsIfCalled).acceptExternal("a@corp.test", "body");
            throw new AssertionError("external scanner was not reached");
        } catch (ProbeException expected) {
            // Expected: external ingress must reach the scanner.
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }

    private static final class RecordingScanner implements ContentScanner {
        private final boolean result;
        private int calls;
        private String lastBody;

        private RecordingScanner(boolean result) {
            this.result = result;
        }

        @Override
        public boolean allows(String body) {
            calls += 1;
            lastBody = body;
            return result;
        }
    }

    private static final class ProbeException extends RuntimeException {
    }
}
