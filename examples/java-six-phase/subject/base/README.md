# Ingress gateway

This small Java 17 component accepts messages from two ingress channels.

- `acceptInternal` is called only after internal authentication and may bypass content scanning.
- `acceptExternal` accepts caller-controlled sender text and must pass the body through the scanner.

Compile the sources with `javac --release 17` and run
`dev.bat.examples.ingress.IngressGatewayPublicTest` for the public assertion suite.
