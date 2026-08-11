# Spike — running a datastore Bazel test offline, in a container

POC notes. Local machine only. Measured 2026-08-11 on the dev-vm.

## Result

**On the host, offline works.** `bazel test --nofetch` on a focused datastore target passed:

```
//core/datastore-manager:TopologyChangeManagerTest    PASSED in 2.5s
Elapsed time: 73.306s, 561 processes, Build completed successfully
```

Bazel 7.4.0, no network fetching, 148 production and 78 test sources compiled from the warm
repository cache. 73 s for a cold-ish focused target is comfortably inside BDR's `2 + 3N` cadence —
a three-slice run is roughly eleven invocations.

**In a networkless container, not yet.** Three blockers found, each an image-content requirement
rather than an unknown. Network isolation itself was verified working (`--network none`, no DNS).

## The blocker chain, in the order it appears

1. **bazelisk needs the network** to resolve a version. Use the pinned binary directly:
   `~/.cache/bazelisk/downloads/bazelbuild/bazel-7.4.0-linux-x86_64/bin/bazel`.

2. **The workspace must be mounted at the identical host path.** Bazel derives its output base from
   the workspace path, so mounting the checkout at `/src` produced a *different* output base with no
   cached external repositories, and `--nofetch` then failed on `@@bazel_tools not found`. Mounting
   at `/home/pavel/git/datastore` resolved the external repos immediately. This is the
   non-obvious one.

3. **The cached `local_config_*` repositories bake in host toolchain paths.** With the JDK absent the
   build aborted on `/usr/lib/jvm/java-25-openjdk-amd64 is no longer an existing directory`. Mounting
   the host JDK at its own path got past it, and the next layer is the same problem for the C
   toolchain: `execvp(/usr/bin/gcc): No such file or directory`.

So the image needs, at matching paths: a JDK, `gcc` and friends, and whatever else the cached
`local_config_*` repositories reference. Two ways forward, and the second is probably right:

- **Mount the host toolchain** into the container. Fastest, but couples the run to this host's exact
  layout, and each missing tool is discovered one failure at a time.
- **Build the cache inside the image.** Populate the Bazel cache once in the image that will run the
  build, so `local_config_*` refers to paths that genuinely exist there. More setup, no path
  coupling, and it fails once at build time instead of repeatedly at run time.

## Command that reproduces the current state

```bash
docker run --rm --network none --user "$(id -u):$(id -g)" -e HOME=/tmp \
  -v /home/pavel/git/datastore:/home/pavel/git/datastore:ro \
  -v /home/pavel/.cache/bazel:/home/pavel/.cache/bazel \
  -v /usr/lib/jvm/java-25-openjdk-amd64:/usr/lib/jvm/java-25-openjdk-amd64:ro \
  -v ~/.cache/bazelisk/downloads/bazelbuild/bazel-7.4.0-linux-x86_64/bin/bazel:/usr/local/bin/bazel:ro \
  -w /home/pavel/git/datastore --entrypoint /usr/local/bin/bazel eclipse-temurin:21-jdk \
  --output_user_root=/home/pavel/.cache/bazel/_bazel_pavel \
  test --nofetch --color=no --curses=no --noshow_progress --test_output=summary \
  --symlink_prefix=/tmp/bzl- //core/datastore-manager:TopologyChangeManagerTest
```

## What landed in BAT

`JavaBuildAction.BazelTest` and `WorkerOperationKind.BazelTest`, planning:

```
<bazel> --output_user_root=/bat/run/cache/bazel/root test --nofetch
        --repository_cache=/bat/run/cache/bazel/repo
        --disk_cache=/bat/run/cache/bazel/disk
        --color=no --curses=no --noshow_progress --test_output=summary <label>
```

A Bazel selector is a label, not a Java class name, so it has its own grammar and is **mandatory** —
Bazel cannot run "everything" implicitly, and an unfocused run is not the focused selection a BDR
phase asked for. `//...` is rejected for the same reason: it would turn one phase gate into a
full-repo build.

## Caveats

- The datastore checkout used here had 18 dirty files. A real run needs a clean pinned base commit.
- The mount paths above are this machine's. They are POC scaffolding, not a portable contract.
