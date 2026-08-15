#!/usr/bin/env python3
"""Render / verify a boundary-driven-refactoring slice tree.

  ./slices.py             tree + phase progress
  ./slices.py --check     validate the tracker against itself
  ./slices.py --rules     print what --check does and does NOT catch
  ./slices.py --selftest  mutation-test the validator itself

WHY --rules AND --selftest EXIST
--------------------------------
`--check` printing "consistent" is a claim about the RULES IT HAPPENS TO CONTAIN, not about
the tracker being correct. That distinction is not academic: a real session lost time because
a validator reported green while two findings had been re-assigned in prose and never moved,
and the renderer was reading a stale field. The green was accurate and useless.

So: never report a green check without knowing its coverage. `--rules` prints the coverage.
`--selftest` proves it — it corrupts a copy of the tracker in known ways and asserts each
corruption is caught. A rule that cannot fail is not a rule, and a validator with no selftest
is an unfalsifiable claim about a file full of falsifiable claims.
"""
import copy
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
# Fall back to the template so --selftest is runnable in a fresh checkout, before any real
# tracker exists. The selftest is about the RULES, so any well-formed tracker will do.
TRACKER = next((p for p in (HERE / "slices_progress.yaml",
                            HERE / "slices_progress.template.yaml") if p.exists()), None)
SRC = TRACKER.read_text() if TRACKER else ""
DOC = yaml.safe_load(SRC) if SRC else {}

PHASES = ["expose", "represent", "route", "collapse", "saturate", "falsify"]
MARK = {"pending": "○", "in_progress": "◐", "done": "●", "blocked": "✗"}
C = {"dim": "\033[2m", "bold": "\033[1m", "red": "\033[31m", "grn": "\033[32m",
     "yel": "\033[33m", "cyn": "\033[36m", "off": "\033[0m"}


def c(k, s):
    return f"{C[k]}{s}{C['off']}"


# ---------------------------------------------------------------------------
# RULES. Each is a pure function (doc, src) -> list[problem strings].
#
# `catches` is the point of the rule, in one line. `instance` is the concrete thing that
# went wrong and produced it — a rule without its instance decays into folklore, cannot be
# checked, and gets cargo-culted into situations it was never about.
# ---------------------------------------------------------------------------

def r1_kill_list_resolves(doc, src):
    """kill_list ids exist and agree on ownership."""
    out, findings = [], doc.get("findings") or {}
    for s in doc.get("slices") or []:
        for fid in s.get("kill_list") or []:
            f = findings.get(fid)
            if f is None:
                out.append(f"slice {s['id']} kills #{fid}, which has no findings entry")
            elif f.get("slice") != s["id"]:
                out.append(f"#{fid} is in slice {s['id']}'s kill_list "
                           f"but records slice={f.get('slice')}")
    return out


def r2_findings_are_owned(doc, src):
    """Every finding naming a slice appears in that slice's kill_list."""
    out = []
    kills = {s["id"]: set(s.get("kill_list") or []) for s in doc.get("slices") or []}
    for fid, f in (doc.get("findings") or {}).items():
        sid = f.get("slice")
        if sid is not None and fid not in kills.get(sid, set()):
            out.append(f"#{fid} records slice={sid} but is not in that slice's kill_list")
    return out


def r3_done_means_all_phases(doc, src):
    """A done slice ran all six phases."""
    out = []
    for s in doc.get("slices") or []:
        if s.get("status") != "done" or not s.get("loop_applies", True):
            continue
        unfinished = [p for p in PHASES
                      if (s.get("phases") or {}).get(p, {}).get("status") != "done"]
        if unfinished:
            out.append(f"slice {s['id']} is done but phases not done: {', '.join(unfinished)}")
    return out


def r4_done_owns_no_open(doc, src):
    """A done slice owns no `open` or `dormant` finding — note or no note."""
    out, findings = [], doc.get("findings") or {}
    for s in doc.get("slices") or []:
        if s.get("status") != "done":
            continue
        for fid in s.get("kill_list") or []:
            st = findings.get(fid, {}).get("status")
            if st in ("open", "dormant"):
                out.append(f"slice {s['id']} is done but owns #{fid} with status={st} — "
                           f"transfer it or re-file the remainder under its own id")
    return out


def r5_meta_agrees_on_blocking(doc, src):
    """meta's blocking/optional lists agree with the per-slice merge_blocking flags."""
    out = []
    meta = doc.get("meta") or {}
    blocking = set(meta.get("merge_blocking_slices") or [])
    optional = set(meta.get("optional_slices") or [])
    ids = {s["id"] for s in doc.get("slices") or []}
    for s in doc.get("slices") or []:
        sid, flag = s["id"], s.get("merge_blocking", True)
        if flag and sid not in blocking:
            out.append(f"slice {sid} has merge_blocking: true "
                       f"but meta.merge_blocking_slices omits it")
        if not flag and sid not in optional:
            out.append(f"slice {sid} has merge_blocking: false "
                       f"but meta.optional_slices omits it")
    for sid in (blocking | optional) - ids:
        out.append(f"meta names slice {sid}, which does not exist")
    return out


def r6_orphans_state_a_reason(doc, src):
    """An unassigned open finding records WHY it is unassigned."""
    out = []
    cross = {x["issue"] for x in doc.get("cross_cutting") or []}
    for fid, f in (doc.get("findings") or {}).items():
        if f.get("status") == "open" and f.get("slice") is None \
                and fid not in cross and not f.get("unassigned"):
            out.append(f"#{fid} is open and belongs to no slice — assign it, or record "
                       f"`unassigned: <why>` if that is deliberate")
    return out


def r7_no_duplicate_keys(doc, src):
    """No repeated key in one mapping (YAML silently drops the earlier value)."""
    out = []
    if not src:
        return out

    class Loader(yaml.SafeLoader):
        pass

    dupes = []

    def mapping(loader, node, deep=False):
        seen = set()
        for kn, _ in node.value:
            k = loader.construct_object(kn, deep=deep)
            if k in seen:
                dupes.append((k, kn.start_mark.line + 1))
            seen.add(k)
        return yaml.SafeLoader.construct_mapping(loader, node, deep)

    Loader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
    try:
        yaml.load(src, Loader=Loader)
    except yaml.YAMLError as e:
        out.append(f"tracker does not parse: {e}")
        return out
    for k, line in dupes:
        out.append(f"duplicate key `{k}` at line {line} — the earlier value is lost on load")
    return out


def r8_transfers_are_re_derived(doc, src):
    """A finding moved between slices had its K re-derived at the RECEIVING slice."""
    out = []
    for fid, f in (doc.get("findings") or {}).items():
        if not f.get("routed_from"):
            continue
        if f.get("k_verified_at") != f.get("slice"):
            out.append(
                f"#{fid} was routed from slice {f['routed_from']} to {f.get('slice')} but "
                f"k_verified_at={f.get('k_verified_at')} — re-derive its K against the CODE at "
                f"the receiving slice and record it, or the transfer is grouping by hearsay")
    return out


def r9_no_stub_claims_past_represent(doc, src):
    """A slice past REPRESENT has real collapse predictions, not a TBD stub."""
    out = []
    for s in doc.get("slices") or []:
        if not s.get("loop_applies", True):
            continue
        ph = s.get("phases") or {}
        started = [p for p in ("route", "collapse", "saturate", "falsify")
                   if ph.get(p, {}).get("status") in ("in_progress", "done")]
        if not started:
            continue
        preds = ((s.get("collapse_claim") or {}).get("predictions")) or []
        if not preds or any("TBD" in str(p) for p in preds):
            out.append(
                f"slice {s['id']} has reached {started[0]} with collapse predictions still "
                f"a stub — derive them from each kill-list finding's stated fix directions "
                f"BEFORE routing, or an unbuilt item leaves no corpse for COLLAPSE to miss")
    return out


def r10_collapse_recorded_death(doc, src):
    """A done slice recorded what died, or stated explicitly that nothing should."""
    out = []
    for s in doc.get("slices") or []:
        if s.get("status") != "done" or not s.get("loop_applies", True):
            continue
        col = (s.get("phases") or {}).get("collapse") or {}
        if not col.get("died") and not col.get("no_death_expected"):
            out.append(
                f"slice {s['id']} is done but COLLAPSE recorded no `died` list and no "
                f"`no_death_expected: <why>` — nothing dying means the representation is not "
                f"earning its keep, and that has to be said out loud rather than left blank")
    return out


def r11_one_home_for_collapse_outcome(doc, src):
    """The collapse outcome lives on the phase only — never also top-level."""
    out = []
    for s in doc.get("slices") or []:
        top = (s.get("collapse_claim") or {}).get("outcome")
        phase = ((s.get("phases") or {}).get("collapse") or {}).get("outcome")
        if top and phase and top != phase:
            out.append(f"slice {s['id']} records collapse outcome twice and they disagree "
                       f"(top-level={top}, phase={phase}) — the phase is authoritative")
        elif top and top != "unverified" and not phase:
            out.append(f"slice {s['id']} records collapse outcome={top} top-level only; it "
                       f"belongs on phases.collapse.outcome where COLLAPSE actually writes it")
    return out


def r12_no_assumed_foreign_facts(doc, src):
    """No slice past REPRESENT depends on an unverified foreign-API assumption."""
    out = []
    facts = doc.get("foreign_facts") or []
    started = set()
    for sl in doc.get("slices") or []:
        ph = sl.get("phases") or {}
        if any(ph.get(p, {}).get("status") in ("in_progress", "done")
               for p in ("route", "collapse", "saturate", "falsify")):
            started.add(sl["id"])
    for f in facts:
        if f.get("established") != "assumed":
            continue
        for sid in f.get("depended_on_by") or []:
            if sid in started:
                out.append(
                    f"slice {sid} has reached ROUTE while depending on an ASSUMED foreign fact "
                    f"({f.get('symbol')}): {f.get('assumed')} — measure it, eliminate the "
                    f"dependency, or document it WITH a check. If wrong: "
                    f"{f.get('consequence_if_wrong', 'consequence not recorded')}")
    return out


def r13_foreign_facts_are_evidenced(doc, src):
    """Every foreign fact records its disposition, its evidence, and the cost of being wrong."""
    out = []
    allowed = {"measured", "documented", "eliminated", "assumed"}
    ids = {s["id"] for s in doc.get("slices") or []}
    for f in doc.get("foreign_facts") or []:
        sym = f.get("symbol", "<no symbol>")
        est = f.get("established")
        if est not in allowed:
            out.append(f"foreign fact {sym} has established={est!r}; expected one of "
                       f"{sorted(allowed)}")
        if not f.get("assumed"):
            out.append(f"foreign fact {sym} does not say WHAT is assumed about it")
        if not f.get("consequence_if_wrong"):
            out.append(f"foreign fact {sym} does not record the consequence of being wrong — "
                       f"that is what decides whether to measure it or design it away")
        if est in ("measured", "documented", "eliminated") and not f.get("evidence"):
            out.append(f"foreign fact {sym} claims established={est} with no evidence; a "
                       f"measured fact records the OBSERVED VALUE, an eliminated one names the "
                       f"check that enforces it, a documented one cites the doc AND its check")
        if est == "documented" and not f.get("checked_by"):
            out.append(f"foreign fact {sym} rests on documentation with no checked_by — "
                       f"documentation decays silently on the next upgrade, so it only counts "
                       f"alongside a check in the code")
        for sid in f.get("depended_on_by") or []:
            if sid not in ids:
                out.append(f"foreign fact {sym} is depended on by slice {sid}, which does not exist")
    return out


def r14_route_accounts_for_what_it_added(doc, src):
    """A slice that reached COLLAPSE records what it INTRODUCED, not only what died."""
    out = []
    for sl in doc.get("slices") or []:
        if not sl.get("loop_applies", True):
            continue
        ph = sl.get("phases") or {}
        if ph.get("collapse", {}).get("status") not in ("in_progress", "done"):
            continue
        route = ph.get("route") or {}
        introduced = route.get("introduced")
        if introduced is None:
            out.append(
                f"slice {sl['id']} tallied what died but never recorded what it ADDED — "
                f"phases.route.introduced is missing. Use `introduced: []` if genuinely nothing "
                f"new was built. Every phase in this loop measures removal; nothing measures the "
                f"blast radius of new machinery, which is where a refactor's risk actually lives")
            continue
        for item in introduced or []:
            if not isinstance(item, dict):
                out.append(f"slice {sl['id']} has an `introduced` entry that is not a mapping: "
                           f"{item!r}; each needs what/blast_radius/riskier_than_the_defect")
                continue
            for field in ("what", "blast_radius", "riskier_than_the_defect"):
                if not item.get(field):
                    out.append(
                        f"slice {sl['id']} introduced \"{item.get('what', '?')}\" without "
                        f"`{field}`. The comparison against the defect's own blast radius is the "
                        f"point: trading a loud cheap bug for a quiet expensive one is a net loss "
                        f"that every other phase would score as a win")
    return out


def r15_deviations_from_stated_fixes_are_recorded(doc, src):
    """Building something other than a finding's stated fix direction is recorded as such."""
    out = []
    for sl in doc.get("slices") or []:
        ph = sl.get("phases") or {}
        route = ph.get("route") or {}
        for item in route.get("introduced") or []:
            if not isinstance(item, dict):
                continue
            dev = item.get("deviates_from_stated_fix")
            if dev is None:
                out.append(
                    f"slice {sl['id']} introduced \"{item.get('what', '?')}\" without saying "
                    f"whether it follows the finding's stated fix direction. Set "
                    f"`deviates_from_stated_fix: false`, or describe the departure and why — "
                    f"R9 makes you DERIVE predictions from stated fixes and nothing made you "
                    f"record DEPARTING from one")
    return out


def r16_findings_state_k_and_its_kind(doc, src):
    """Every finding a slice owns records its missing fact K, and whether K is value or temporal."""
    out = []
    kinds = {"value", "temporal"}
    owned = set()
    for sl in doc.get("slices") or []:
        owned |= set(sl.get("kill_list") or [])
    for fid in sorted(owned):
        f = (doc.get("findings") or {}).get(fid) or {}
        if not f.get("k"):
            out.append(f"#{fid} is owned by a slice but records no `k` — the one sentence "
                       f"\"at <site> the code needs to know K and infers K from I\". Without it "
                       f"the grouping cannot be checked against anything")
        kind = f.get("k_kind")
        if kind not in kinds:
            out.append(f"#{fid} has k_kind={kind!r}; expected one of {sorted(kinds)}. A VALUE K "
                       f"becomes a record; a TEMPORAL K needs an epoch, scope or phase token. "
                       f"Fifteen value Ks closed cleanly on the source codebase and all nine "
                       f"survivors were temporal, so the distinction predicts tractability")
    return out


def r18_temporal_slices_represent_the_window(doc, src):
    """A slice killing a temporal K names the value carrying the validity window."""
    out = []
    findings = doc.get("findings") or {}
    for sl in doc.get("slices") or []:
        temporal = [fid for fid in sl.get("kill_list") or []
                    if (findings.get(fid) or {}).get("k_kind") == "temporal"]
        if not temporal:
            continue
        if not sl.get("temporal_representation"):
            out.append(
                f"slice {sl['id']} kills temporal findings {temporal} but records no "
                f"`temporal_representation` — the epoch, generation, scope or phase token that "
                f"makes the validity window part of the type. If the fix is a change in control "
                f"flow rather than in data, the temporal K was patched, not represented")
        if not sl.get("runtime_check"):
            out.append(
                f"slice {sl['id']} kills temporal findings {temporal} but records no "
                f"`runtime_check`. Java has no region or ownership types, so a validity window "
                f"cannot be enforced by the compiler; the representation must carry a check that "
                f"fails loudly. A comment asserting the same thing is the crutch this replaces")
    return out


STRUCTURES = {"ownership", "borrow", "lease", "capability", "work_ownership",
              "reservation", "projection", "completion", "irreducible"}


def r17_temporal_ks_name_their_structure(doc, src):
    """A temporal K records which ownership structure it dissolves to. `temporal` is a TODO."""
    out = []
    owned = set()
    for sl in doc.get("slices") or []:
        owned |= set(sl.get("kill_list") or [])
    for fid in sorted(owned):
        f = (doc.get("findings") or {}).get(fid) or {}
        if f.get("k_kind") != "temporal":
            continue
        d = f.get("dissolves_to")
        if d not in STRUCTURES:
            out.append(
                f"#{fid} is marked temporal but dissolves_to={d!r}; expected one of "
                f"{sorted(STRUCTURES)}. There is no temporal K — only a K whose ownership "
                f"structure has not been found. Rust's lifetimes are regions, not durations: it "
                f"reasons about containment, never about when. Eight of nine findings that read "
                f"as temporal on the source codebase dissolved into one of these")
        elif d == "irreducible" and not f.get("irreducible_because"):
            out.append(
                f"#{fid} claims its temporal K is irreducible without saying why. Only the clock "
                f"itself qualified on the source codebase; everything else was ownership, a "
                f"borrow, a lease, a capability, a reservation, a projection or a completion")
    return out


def r19_split_findings_name_their_remainder(doc, src):
    """A `split` finding names the id its remainder was filed under."""
    out = []
    for fid, f in (doc.get("findings") or {}).items():
        if f.get("status") != "split":
            continue
        remainder = f.get("remainder_filed_as")
        if not remainder:
            out.append(
                f"#{fid} is status: split — by definition the parent is DONE and the remainder "
                f"lives under its own id — but records no `remainder_filed_as`. Without it, the "
                f"parent's issue reads as settled while the work it split off stays untracked")
        elif remainder == fid:
            out.append(
                f"#{fid}'s remainder_filed_as points at itself — a remainder has to be a "
                f"DIFFERENT id, or it never actually left the parent")
    return out


RULES = [
    ("R1", r1_kill_list_resolves,
     "a kill_list naming a finding that does not exist, or that the finding disowns",
     "routine typo class"),
    ("R2", r2_findings_are_owned,
     "a finding that thinks it belongs to a slice the slice has never heard of",
     "the other direction of R1; one-sided edits are the common failure"),
    ("R3", r3_done_means_all_phases,
     "a slice called done that skipped a phase",
     "COLLAPSE and FALSIFY are the skippable ones and the only ones that can invalidate work"),
    ("R4", r4_done_owns_no_open,
     "a done slice still holding an open/dormant finding",
     "the rule used to accept 'open, but there is a note'. Two findings were deferred in prose "
     "to a later slice and never moved; the note WAS the deferral, so the evidence of the "
     "unfinished transfer was accepted as the excuse for it. The receiving slice would have "
     "declared its boundary verified with both findings live"),
    ("R5", r5_meta_agrees_on_blocking,
     "meta's merge-blocking list disagreeing with the per-slice flags",
     "two homes for one fact; the renderer read a different one for the header than for the tag, "
     "so they drifted while the tree looked coherent"),
    ("R6", r6_orphans_state_a_reason,
     "an open finding in no slice with no stated reason",
     "'unassigned by decision' and 'nobody decided yet' are identical in the data and need "
     "opposite responses, so the reason has to be a field"),
    ("R7", r7_no_duplicate_keys,
     "a repeated YAML key silently discarding the earlier value, and parse failures",
     "a finding carried two `done:` keys; the first was dropped on load with no error"),
    ("R8", r8_transfers_are_re_derived,
     "a finding transferred between slices on hearsay, without re-deriving its K",
     "a finding was routed to a slice because it lived in the same unseamed REGION as that "
     "slice's findings. Region is exactly what the boundary procedure says not to group by. "
     "Reading the code showed its K belonged to a different slice entirely"),
    ("R9", r9_no_stub_claims_past_represent,
     "routing or collapsing while the collapse claim is still a TBD stub",
     "a claim written from the build plan rather than the findings cannot notice an item that "
     "was never built, because unbuilt items leave no surviving code"),
    ("R10", r10_collapse_recorded_death,
     "a done slice that never recorded what died",
     "'nothing collapsed' is the signal that the representation is unnecessary — a blank died "
     "list hides that instead of raising it"),
    ("R11", r11_one_home_for_collapse_outcome,
     "the collapse outcome recorded in two places",
     "outcomes were written to phases.collapse.outcome while the renderer read the top-level "
     "planning stub, so confirmed slices displayed as unverified for an entire session"),
    ("R12", r12_no_assumed_foreign_facts,
     "routing or collapsing while a foreign-API assumption is still unverified",
     "an assumption about a third-party call is an UNREPRESENTED FACT — the same defect shape "
     "this method hunts, pointed outward. Every third-party semantic assumed during the source "
     "session was wrong: a package-private constructor, instance recycling, content-based "
     "equals, a sealed interface, chunk-granular metrics reporting 4 MiB for 100 bytes, and an "
     "iterator assumed to hold still across a seek. The prose guardrail fired only when it was "
     "already remembered, which is not a guardrail"),
    ("R16", r16_findings_state_k_and_its_kind,
     "a finding with no stated K, or no value/temporal classification",
     "on the source codebase every one of fifteen VALUE Ks closed cleanly with one representation "
     "each, and every one of the nine survivors was TEMPORAL — lifetime, lock scope, pinned "
     "duration, scheduling, liveness, the instant a reading was taken, the staleness of a mirror. "
     "The kind predicts tractability, so it has to be written down before the slice is planned"),
    ("R17", r17_temporal_ks_name_their_structure,
     "a temporal K shipped without naming the ownership structure it dissolves to",
     "Rust and modern C++ solve what FP alone does not, and not by modelling time: a lifetime is a "
     "REGION, and borrow checking is a static analysis over containment. Of nine findings that read "
     "as temporal on the source codebase, eight dissolved into ownership, a borrow, a lease, a "
     "capability, work-ownership, a reservation, a projection or a completion — and two of those "
     "dissolutions overturned conclusions already written down as unavoidable, including \"a "
     "time-of-check/time-of-use gap is inherent here\" (hold a reservation instead) and \"the "
     "ledger will always drift\" (derive it from the owner set instead). Only the clock was real"),
    ("R18", r18_temporal_slices_represent_the_window,
     "a temporal K fixed by control flow instead of by a value carrying its window",
     "every crutch in that codebase was a temporal fact expressed as flow: a full scan called from "
     "a read path instead of an index generation, work run inline on whoever's thread arrived "
     "instead of a schedule, a javadoc reading \"valid only for this call\" instead of a scope"),
    ("R14", r14_route_accounts_for_what_it_added,
     "a slice that tallied what died but never accounted for what it built",
     "every phase in this loop measures removal — COLLAPSE asks what died, SATURATE tests the new "
     "value's boundaries, FALSIFY asks whether siblings became unreachable — and the strongest "
     "signal it recognises, an unpredicted collapse, rewards deletion. So a full column-family "
     "scan added to a READ path, which also overwrote two shared tracker fields, passed through "
     "all three phases unremarked. A refactor's risk is in what it adds; deletion is validated by "
     "the suite still passing"),
    ("R15", r15_deviations_from_stated_fixes_are_recorded,
     "building something other than a finding's stated fix without saying so",
     "an issue recommended marking an index degraded and rebuilding it IN THE BACKGROUND. What got "
     "built was a synchronous rebuild on the reader's thread — neither of the options the issue "
     "offered. R9 forces predictions to be DERIVED from stated fix directions; nothing forced the "
     "departure to be recorded, so it was invisible for a week"),
    ("R13", r13_foreign_facts_are_evidenced,
     "a foreign fact with no disposition, no evidence, or no stated cost of being wrong",
     "'documented' decays silently on the next upgrade, so it only counts alongside a check. "
     "Preference order: ELIMINATE the dependency > MEASURE it > DOCUMENT it with a check > "
     "assumed, which blocks. Eliminating beats measuring because a measurement is a "
     "point-in-time act and a checked invariant travels with the code"),
    ("R19", r19_split_findings_name_their_remainder,
     "a finding with status split that does not say what id its remainder was filed under",
     "three findings (#893, #902, #904) sat split for days with remainders that existed only as "
     "prose in their own `remaining:` field, under no issue number — so 'the parent is done' was "
     "asserted while the work it split off was untracked by anything. Same shape as R4: a "
     "disposition recorded but not carried out"),
]

# What this validator DELIBERATELY does not check. Read this before trusting a green run.
NOT_CHECKED = [
    "whether a boundary statement is TRUE of the code — only the code can say",
    "whether a kill list is grouped by missing fact (K) rather than by file/region/symptom; "
    "R8 only catches transfers, not an original mis-grouping",
    "whether a `died` entry actually died — verify by building at the parent commit",
    "whether a test would fail if its fix were reverted; SATURATE claims are taken on trust",
    "whether the tracker describes the same codebase you are looking at",
    "whether the foreign_facts ledger is COMPLETE. R12/R13 check the entries that exist; "
    "nothing detects a dependency you never wrote down. Ask at every phase boundary: what did "
    "this phase newly assume about code I do not own?",
]


def phase_bar(sl):
    ph = sl.get("phases")
    if not ph:
        return c("dim", "enabler — loop N/A")
    bar = "".join(MARK.get(ph.get(p, {}).get("status", "pending"), "?") for p in PHASES)
    done = sum(1 for p in PHASES if ph.get(p, {}).get("status") == "done")
    col = "grn" if done == len(PHASES) else ("yel" if done else "dim")
    return f"{c(col, bar)} {c('dim', f'{done}/{len(PHASES)}')}"


def main():
    m = DOC.get("meta") or {}
    slices = DOC.get("slices") or []
    print()
    title = m.get("title") or (f"PR #{m['pr']}" if m.get("pr") else "slice tree")
    sub = "{} findings · {} slices · updated {}".format(
        m.get("findings_filed", len(DOC.get("findings") or {})), len(slices),
        m.get("last_updated", "?"))
    print("  {}   {}".format(c("bold", title), c("dim", sub)))
    if m.get("merge_blocking_slices") is not None:
        print("  " + c("dim", "merge-blocking: {}   optional: {}".format(
            ",".join(map(str, m.get("merge_blocking_slices") or [])),
            ",".join(map(str, m.get("optional_slices") or [])))))
    print()

    unblocks = {s["id"]: [] for s in slices}
    for s in slices:
        for d in s.get("depends_on", []):
            unblocks.setdefault(d, []).append(s["id"])

    findings = DOC.get("findings") or {}
    glyph = {"fixed": ("grn", "✓"), "split": ("grn", "⊂"), "blocked": ("yel", "⊘"),
             "dormant": ("red", "!"), "open": ("red", "○"), "superseded": ("dim", "-")}

    for s in slices:
        sid, st = s["id"], s.get("status", "pending")
        flag = "" if s.get("merge_blocking", True) else c("dim", " [optional]")
        print(f"  {c('cyn', f'[{sid}]')} {c('bold', s['name'])}{flag}")
        print(f"       {MARK.get(st,'?')} {st:<12} {phase_bar(s)}")
        if s.get("depends_on"):
            hard = " " + c("red", f"(HARD: {s['hard_order']})") if s.get("hard_order") else ""
            print(f"       {c('dim','deps:')} {','.join(map(str, s['depends_on']))}{hard}")
        if unblocks.get(sid):
            print(f"       {c('dim','unblocks:')} {','.join(map(str, unblocks[sid]))}")
        kl = s.get("kill_list") or []
        if kl:
            parts = [c(*glyph.get(findings.get(i, {}).get("status", "?"), ("dim", "?"))[:1],
                       f"#{i}{glyph.get(findings.get(i, {}).get('status', '?'), ('dim','?'))[1]}")
                     for i in kl]
            print(f"       {c('dim','kills:')} {' '.join(parts)}")
        else:
            owned = s.get("owns_remainders_of")
            print(f"       {c('dim','kills:')} "
                  + (c("dim", f"— (owns remainders of {owned})") if owned
                     else c("dim", "— (test debt / enabler)")))

        # The outcome belongs on the phase (that is where COLLAPSE writes it). Fall back to the
        # top-level block only for trackers predating that convention — see R11.
        cc = s.get("collapse_claim") if isinstance(s.get("collapse_claim"), dict) else {}
        ph_cc = (s.get("phases") or {}).get("collapse") or {}
        out = ph_cc.get("outcome") or cc.get("outcome") or "unverified"
        preds = cc.get("predictions") or []
        stub = any("TBD" in str(p) for p in preds)
        ocol = {"confirmed": "grn", "refuted": "red",
                "partially_refuted": "yel"}.get(out, "dim")
        pred_note = c("red", f"({len(preds)} predicted — STUB, never backfilled)") if stub \
            else c("dim", f"({len(preds)} predicted)")
        died = ph_cc.get("died") or []
        print(f"       {c('dim','collapse claim:')} {c(ocol, out)} {pred_note}"
              + (c("dim", f" · {len(died)} died") if died else ""))
        if s.get("residual_reads"):
            print(f"       {c('dim','residual reads:')} "
                  f"{c('dim', '; '.join(s['residual_reads']))}")
        print()

    for x in DOC.get("cross_cutting") or []:
        print(f"  {c('yel','~')} cross-cutting {c('cyn','#'+str(x['issue']))} "
              f"{c('dim','(running list, not a slice)')}")
    print()


def run_rules(doc, src):
    return [(rid, p) for rid, fn, _, _ in RULES for p in fn(doc, src)]


def check():
    print(f"  {c('bold','consistency check')} {c('dim','— this file against itself')}\n")
    problems = run_rules(DOC, SRC)
    for rid, p in problems:
        print(f"  {c('red','x')} {c('dim',rid)} {p}")
    if not problems:
        print(f"  {c('grn','consistent')} {c('dim', f'against {len(RULES)} rules')}")
        print(f"  {c('dim', 'that is a claim about the rules, not about the work — ./slices.py --rules')}")
    by_status = {}
    for f in (DOC.get("findings") or {}).values():
        by_status[f.get("status", "?")] = by_status.get(f.get("status", "?"), 0) + 1
    print()
    print("  " + c("dim", "findings: " + "  ".join(
        f"{k}={v}" for k, v in sorted(by_status.items()))))
    print()
    return problems


def rules():
    print(f"\n  {c('bold','what --check catches')}\n")
    for rid, _, catches, instance in RULES:
        print(f"  {c('cyn',rid)} {catches}")
        print(f"       {c('dim','paid for by: ' + instance)}\n")
    print(f"  {c('bold','what it does NOT catch')} "
          f"{c('dim','— a green run is silent about all of this')}\n")
    for n in NOT_CHECKED:
        print(f"  {c('yel','?')} {n}")
    print()


# ---------------------------------------------------------------------------
# SELFTEST — corrupt a copy in known ways, assert the right rule fires.
# This is the validator's own SATURATE. Without it, "consistent" is unfalsifiable.
# ---------------------------------------------------------------------------

# A minimal VALID tracker the mutations are applied to. Deliberately not the real one: the
# selftest is about whether each rule fires, so it must not depend on the live tracker
# happening to contain a done slice, an orphan, or any other shape.
FIXTURE_SRC = """\
meta:
  project: fixture
  last_updated: "2000-01-01"
  merge_blocking_slices: [0]
  optional_slices: [1]
foreign_facts:
  - symbol: "Lib.thing()"
    api: "some library"
    assumed: "returns bytes, not chunks"
    established: eliminated
    evidence: "callers compare against an independently derived count and throw on mismatch"
    consequence_if_wrong: "a bound computed from the wrong unit"
    depended_on_by: [0]
findings:
  10: {slice: 0, status: fixed, what: "K inferred from I",
       k: "does the caller own this?", k_kind: value}
  11: {slice: 1, status: open, what: "K inferred from I",
       k: "how long are these bytes valid?", k_kind: temporal, dissolves_to: borrow}
slices:
  - id: 0
    name: done slice
    status: done
    loop_applies: true
    merge_blocking: true
    kill_list: [10]
    collapse_claim:
      predictions: ["the inference at Foo.java:1 disappears"]
    phases:
      expose:    {status: done}
      represent: {status: done}
      route:
        status: done
        introduced:
          - what: "a thin adapter over Lib.thing()"
            blast_radius: "one call site, pure"
            riskier_than_the_defect: "no — pure function, no new IO or shared state"
            deviates_from_stated_fix: false
      collapse:  {status: done, outcome: confirmed, died: ["the inference at Foo.java:1"]}
      saturate:  {status: done}
      falsify:   {status: done}
  - id: 1
    name: pending slice
    status: pending
    temporal_representation: "a Scope token handed to the holder, valid for one visit"
    runtime_check: "the holder asserts its scope is still open before dereferencing"
    loop_applies: true
    merge_blocking: false
    kill_list: [11]
    collapse_claim:
      predictions: ["TBD — fill during EXPOSE"]
    phases:
      expose:    {status: pending}
      represent: {status: pending}
      route:     {status: pending}
      collapse:  {status: pending}
      saturate:  {status: pending}
      falsify:   {status: pending}
"""
FIXTURE = yaml.safe_load(FIXTURE_SRC)


def _first_slice(d, pred=lambda s: True):
    return next(s for s in d["slices"] if pred(s))


def _mut_break_kill_list(d, src):
    _first_slice(d)["kill_list"] = [999999]
    return d, src


def _mut_disown_finding(d, src):
    s = _first_slice(d, lambda s: s.get("kill_list"))
    d["findings"][s["kill_list"][0]]["slice"] = 424242
    return d, src


def _mut_unfinished_done_slice(d, src):
    s = _first_slice(d, lambda s: s.get("status") == "done" and s.get("loop_applies", True))
    s["phases"]["falsify"]["status"] = "pending"
    return d, src


def _mut_done_owns_open(d, src):
    s = _first_slice(d, lambda s: s.get("status") == "done" and s.get("kill_list"))
    d["findings"][s["kill_list"][0]].update(status="open", note="a note, which is not an excuse")
    return d, src


def _mut_meta_drift(d, src):
    s = _first_slice(d)
    s["merge_blocking"] = True
    d["meta"]["merge_blocking_slices"] = [x for x in
                                          (d["meta"].get("merge_blocking_slices") or [])
                                          if x != s["id"]]
    return d, src


def _mut_silent_orphan(d, src):
    fid = next(iter(d["findings"]))
    d["findings"][fid].update(slice=None, status="open")
    d["findings"][fid].pop("unassigned", None)
    for s in d["slices"]:
        s["kill_list"] = [x for x in (s.get("kill_list") or []) if x != fid]
    return d, src


def _mut_duplicate_key(d, src):
    return d, "meta:\n  pr: 1\n  pr: 2\nslices: []\nfindings: {}\n"


def _mut_hearsay_transfer(d, src):
    fid = next(iter(d["findings"]))
    d["findings"][fid].update(routed_from=1, k_verified_at=None)
    return d, src


def _mut_stub_past_represent(d, src):
    s = _first_slice(d, lambda s: s.get("loop_applies", True))
    s.setdefault("phases", {}).setdefault("route", {})["status"] = "done"
    s["collapse_claim"] = {"predictions": ["TBD — fill during EXPOSE"]}
    return d, src


def _mut_no_death_recorded(d, src):
    s = _first_slice(d, lambda s: s.get("status") == "done" and s.get("loop_applies", True))
    col = s.setdefault("phases", {}).setdefault("collapse", {})
    col.pop("died", None)
    col.pop("no_death_expected", None)
    return d, src


def _mut_assumed_foreign_fact(d, src):
    d["foreign_facts"][0]["established"] = "assumed"
    return d, src


def _mut_unevidenced_foreign_fact(d, src):
    d["foreign_facts"][0].pop("consequence_if_wrong", None)
    return d, src


def _mut_unaccounted_addition(d, src):
    _first_slice(d, lambda s: s["id"] == 0)["phases"]["route"].pop("introduced", None)
    return d, src


def _mut_unjustified_addition(d, src):
    _first_slice(d, lambda s: s["id"] == 0)["phases"]["route"]["introduced"][0].pop(
        "riskier_than_the_defect", None)
    return d, src


def _mut_unrecorded_deviation(d, src):
    _first_slice(d, lambda s: s["id"] == 0)["phases"]["route"]["introduced"][0].pop(
        "deviates_from_stated_fix", None)
    return d, src


def _mut_finding_without_k(d, src):
    d["findings"][10].pop("k", None)
    return d, src


def _mut_finding_without_kind(d, src):
    d["findings"][10]["k_kind"] = "sort of both"
    return d, src


def _mut_temporal_without_structure(d, src):
    d["findings"][11]["dissolves_to"] = "somehow"
    return d, src


def _mut_irreducible_without_reason(d, src):
    d["findings"][11]["dissolves_to"] = "irreducible"
    return d, src


def _mut_temporal_without_window(d, src):
    _first_slice(d, lambda s: s["id"] == 1).pop("temporal_representation", None)
    return d, src


def _mut_temporal_without_check(d, src):
    _first_slice(d, lambda s: s["id"] == 1).pop("runtime_check", None)
    return d, src


def _mut_split_without_remainder(d, src):
    d["findings"][10]["status"] = "split"
    return d, src


def _mut_two_homes(d, src):
    s = _first_slice(d)
    s.setdefault("collapse_claim", {})["outcome"] = "confirmed"
    s.setdefault("phases", {}).setdefault("collapse", {})["outcome"] = "refuted"
    return d, src


MUTATIONS = [
    ("kill_list names a nonexistent finding", _mut_break_kill_list, "R1"),
    ("finding disowns the slice that kills it", _mut_disown_finding, "R2"),
    ("done slice with an unfinished phase", _mut_unfinished_done_slice, "R3"),
    ("done slice owns an open finding, with a note", _mut_done_owns_open, "R4"),
    ("meta blocking list drifts from the flag", _mut_meta_drift, "R5"),
    ("open finding orphaned with no reason", _mut_silent_orphan, "R6"),
    ("duplicate YAML key", _mut_duplicate_key, "R7"),
    ("finding transferred without re-deriving K", _mut_hearsay_transfer, "R8"),
    ("routed with a stub collapse claim", _mut_stub_past_represent, "R9"),
    ("done slice recorded no code death", _mut_no_death_recorded, "R10"),
    ("collapse outcome in two places, disagreeing", _mut_two_homes, "R11"),
    ("routed while a foreign API is still assumed", _mut_assumed_foreign_fact, "R12"),
    ("foreign fact with no stated cost of being wrong", _mut_unevidenced_foreign_fact, "R13"),
    ("collapsed without accounting for what was added", _mut_unaccounted_addition, "R14"),
    ("addition with no risk comparison", _mut_unjustified_addition, "R14"),
    ("addition that never says if it follows the stated fix", _mut_unrecorded_deviation, "R15"),
    ("finding with no stated K", _mut_finding_without_k, "R16"),
    ("finding with an unclassifiable K", _mut_finding_without_kind, "R16"),
    ("temporal K with no dissolution named", _mut_temporal_without_structure, "R17"),
    ("temporal K called irreducible with no reason", _mut_irreducible_without_reason, "R17"),
    ("temporal K with no represented window", _mut_temporal_without_window, "R18"),
    ("temporal K with no runtime check", _mut_temporal_without_check, "R18"),
    ("split finding with no remainder_filed_as", _mut_split_without_remainder, "R19"),
]


def selftest():
    print(f"\n  {c('bold','validator selftest')} "
          f"{c('dim','— does each rule actually fire?')}\n")
    failures = 0
    covered = set()

    baseline = run_rules(FIXTURE, FIXTURE_SRC)
    if baseline:
        # If the fixture is already dirty, every "✓" below is potentially a false positive.
        failures += 1
        print(f"  {c('red','✗')} the fixture itself is not clean — "
              f"{[f'{r}: {p}' for r, p in baseline]}\n")

    for name, mut, expect in MUTATIONS:
        doc, src = mut(copy.deepcopy(FIXTURE), FIXTURE_SRC)
        fired = {rid for rid, _ in run_rules(doc, src)}
        ok = expect in fired
        covered.add(expect)
        print(f"  {c('grn','✓') if ok else c('red','✗')} {c('dim',expect)} {name}"
              + ("" if ok else c("red", f"  — expected {expect}, fired {sorted(fired) or 'nothing'}")))
        failures += 0 if ok else 1

    unexercised = [rid for rid, *_ in RULES if rid not in covered]
    if unexercised:
        failures += len(unexercised)
        print(f"\n  {c('red','✗')} rules with no mutation proving they fire: "
              f"{', '.join(unexercised)}")

    clean = run_rules(DOC, SRC)
    print(f"\n  {c('dim','the real tracker, unmutated:')} "
          + (c("grn", "clean") if not clean else c("yel", f"{len(clean)} problem(s)")))
    print(f"  {'  ' if failures else ''}"
          + (c("red", f"{failures} rule(s) did not fire — coverage is a fiction there")
             if failures else c("grn", f"all {len(RULES)} rules proven to fire")))
    print()
    return failures


if __name__ == "__main__":
    if "--rules" in sys.argv:
        rules()
    elif "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    else:
        main()
        if "--check" in sys.argv:
            sys.exit(1 if check() else 0)
