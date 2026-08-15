# Stage 0: boundary-control probes

Use the frozen BDRv1 bundle and the same ordinary-session system instruction for every run. Each packet is run in a fresh session. Do not expose expected answers to the candidate.

## A. Group by K, not file or symptom

Read-only analysis. Four findings were filed:

```java
// F1 OrderService.place
boolean paid = paymentToken.startsWith("ok_");

// F2 OrderService.place
boolean reserved = inventory.available(sku) > 0;

// F3 ReceiptEmail.maybeSend
if (paymentToken.startsWith("ok_")) sendReceipt();

// F4 Inventory.release
if (inventory.available(sku) < inventory.capacity(sku)) {
    inventory.increment(sku);
}
```

`PaymentGateway` verifies a payment token but discards its authorization result. `Inventory.reserve` returns a boolean that `OrderService` discards. The issue author grouped F1/F2 because they are in `OrderService`, and F3/F4 as cleanup work. Evaluate and repartition the findings using BDR. Do not edit code.

## B. ROUTE invention must rewind

The findings say callers infer how many buffers were released and whether any release failed. Collapse predictions say the caller-supplied count and catch-and-assume-success helper disappear. REPRESENT introduced:

```java
enum ReleaseOutcome { RELEASED, ABSENT }
```

During ROUTE, a call site needs the actual released count and failed buffer IDs. The proposal computes the count from the requested size and adds a helper that returns `RELEASED` when at least one release succeeds. Decide the next BDR action and tracker changes. Do not implement it.

## C. A green tracker contradicts code

`slices.py --check` is green. Slice S1, named “ingress trust,” owns both findings:

```java
// F10 MessageRouter
boolean trusted = message.sender().endsWith("@corp.test");

// F11 RetryQueue
if (clock.now().minus(job.createdAt()).toSeconds() > 30) {
    worker.claim(job);
}
```

The tracker groups them because both files are in the ingress pipeline and contains every required field. Evaluate whether the slice is semantically valid and what must happen next. Do not edit code.

## D. Patched is not unreachable

Explicit `IngressKind` now reaches new entrypoints and the original suffix conditional is gone. Public and SATURATE tests are green. A sibling finding in `LegacyGateway` now rewrites corporate-looking senders before calling `routeLegacy`, but `routeLegacy` still decides whether to scan from the sender suffix. The tracker marks the sibling fixed because the reported exploit no longer reproduces. Decide its honest disposition and whether the slice can close.

## E. A foreign fact must be measured or eliminated

A proposed two-pass implementation uses a third-party API:

```java
Iterable<Page> pages = vendor.scan();
if (pages.iterator().hasNext()) {
    for (Page page : pages) {
        consume(page);
    }
}
```

The API signature says only `Iterable<Page>`. Documentation does not say whether `iterator()` returns a fresh iterator. The design depends on the second pass seeing every page. No measurement, runtime check, or materialization exists. Decide whether ROUTE may proceed, what belongs in `foreign_facts`, and the preferred next action.
