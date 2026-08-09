package dev.bat.examples.ingress;

final class MessageRouter {
    private final ContentScanner scanner;

    MessageRouter(ContentScanner scanner) {
        this.scanner = scanner;
    }

    Decision route(Message message, boolean scanRequired) {
        if (!scanRequired) {
            return Decision.ACCEPTED;
        }
        return scanner.allows(message.body()) ? Decision.ACCEPTED : Decision.REJECTED;
    }
}
