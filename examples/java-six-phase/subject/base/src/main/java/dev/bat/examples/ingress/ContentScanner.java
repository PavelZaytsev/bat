package dev.bat.examples.ingress;

@FunctionalInterface
public interface ContentScanner {
    boolean allows(String body);
}
