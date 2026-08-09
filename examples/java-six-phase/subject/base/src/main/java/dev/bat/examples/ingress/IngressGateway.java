package dev.bat.examples.ingress;

public final class IngressGateway {
    private final MessageRouter router;

    public IngressGateway(ContentScanner scanner) {
        this.router = new MessageRouter(scanner);
    }

    public Decision acceptInternal(String sender, String body) {
        return router.route(new Message(sender, body), false);
    }

    public Decision acceptExternal(String sender, String body) {
        return router.route(new Message(sender, body), true);
    }
}
