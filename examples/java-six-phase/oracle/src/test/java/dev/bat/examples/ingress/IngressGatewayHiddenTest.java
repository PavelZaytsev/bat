package dev.bat.examples.ingress;

public final class IngressGatewayHiddenTest {
    public static void main(String[] args) {
        internalCorporateSenderBypassesScanner();
        externalInternetSenderIsScanned();
        externalCorporateLookingSenderIsStillScanned();
        internalServiceAliasStillBypassesScanner();
        System.out.println("PASS IngressGatewayHiddenTest");
    }

    private static void internalCorporateSenderBypassesScanner() {
        RecordingScanner scanner = new RecordingScanner(false);
        IngressGateway gateway = new IngressGateway(scanner);

        Decision actual = gateway.acceptInternal("alice@corp.test", "trusted update");

        assertEquals(Decision.ACCEPTED, actual, "internal corporate sender");
        assertEquals(0, scanner.calls(), "internal corporate sender scan count");
    }

    private static void externalInternetSenderIsScanned() {
        RecordingScanner scanner = new RecordingScanner(false);
        IngressGateway gateway = new IngressGateway(scanner);

        Decision actual = gateway.acceptExternal("mallory@example.test", "untrusted update");

        assertEquals(Decision.REJECTED, actual, "external internet sender");
        assertEquals(1, scanner.calls(), "external internet sender scan count");
    }

    private static void externalCorporateLookingSenderIsStillScanned() {
        RecordingScanner scanner = new RecordingScanner(false);
        IngressGateway gateway = new IngressGateway(scanner);

        Decision actual = gateway.acceptExternal("mallory@corp.test", "spoofed update");

        assertEquals(Decision.REJECTED, actual, "external corporate-looking sender");
        assertEquals(1, scanner.calls(), "external corporate-looking sender scan count");
    }

    private static void internalServiceAliasStillBypassesScanner() {
        RecordingScanner scanner = new RecordingScanner(false);
        IngressGateway gateway = new IngressGateway(scanner);

        Decision actual = gateway.acceptInternal("batch-job", "scheduled update");

        assertEquals(Decision.ACCEPTED, actual, "internal service alias");
        assertEquals(0, scanner.calls(), "internal service alias scan count");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }

    private static final class RecordingScanner implements ContentScanner {
        private final boolean result;
        private int calls;

        private RecordingScanner(boolean result) {
            this.result = result;
        }

        @Override
        public boolean allows(String body) {
            calls += 1;
            return result;
        }

        private int calls() {
            return calls;
        }
    }
}
