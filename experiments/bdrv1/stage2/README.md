# Stage-2 autonomous repair experiment

This stage resumes the Stage-1 tracker in a fresh session and permits product edits. The model is
given a normal shell, the original BDRv1 bundle, the existing tracker, and the missing product-owner
decision. It is not advanced by a phase controller.

The writable candidate repository must be created as a new single-root Git repository containing
only the target source snapshot. The original fixture history is not copied because its parent tree
would leak a near-solution.

The evaluator remains outside the candidate mount. Acceptance requires all four combinations of
internal/external ingress and corporate/non-corporate sender behavior, both scanner verdicts,
original-body preservation, scanner reachability, public API preservation, explicit provenance
from authority to consumer, removal of sender-derived trust, complete phase evidence, revert
evidence, and an honest validator-clean tracker.

`IngressGatewayAdversarialTest.java` is a hidden evaluator input and must never be mounted into a
candidate session.

Step and token settings are circuit breakers rather than a prescribed reasoning budget. The agent
has unlimited steps and an eight-hour wall-clock ceiling.
