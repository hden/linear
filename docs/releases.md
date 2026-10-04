# Release maintenance

The [release workflow](../.github/workflows/release.yml) runs only when a
GitHub Release is published, including a prerelease. Saving a draft or pushing
a tag does not publish an image. The workflow must be included in the release
commit. See GitHub's [release event documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release).

1. Merge the release changes, create a tag such as `v0.1.0` on the intended
   commit, and push it. Use `vMAJOR.MINOR.PATCH` or a SemVer prerelease such as
   `v0.2.0-rc.1`. Build metadata (`+...`) is not supported by the image tag
   format. Publish a GitHub Release for that existing tag.
2. Wait for **Release image** to succeed. It reuses CI for the release commit,
   then runs the existing Turso SDK E2E against each native architecture's
   packaged application and libraries. Only test files and npm dependency
   manifests are mounted; application source and native libraries come from
   the image. Images are pushed by digest after E2E. Only after both builds
   succeed is `ghcr.io/hden/linear:0.1.0` created. No `latest` or moving minor
   tags are published. A failure can leave untagged digests, but does not
   create a new version tag. Do not reuse or move release tags.
3. On the first publish, open the `linear` package settings under the `hden`
   account and change visibility to **Public**. GHCR packages initially default
   to private; the workflow authenticates with `GITHUB_TOKEN`, and only the
   jobs that push images/manifests receive `packages: write`. See
   [GHCR documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).
4. Read the publish job summary for the image tag and manifest digest. Inspect
   both platforms and test anonymous access with an empty Docker configuration:

   ```sh
   docker buildx imagetools inspect ghcr.io/hden/linear:0.1.0
   docker_config_dir=$(mktemp -d)
   docker --config "$docker_config_dir" pull ghcr.io/hden/linear:0.1.0
   rmdir "$docker_config_dir"
   ```

   Confirm `linux/amd64` and `linux/arm64` in the manifest. Record its digest
   when pinning deployment images. Follow the [deployment guide](deployment.md)
   with your own disposable resources and check both health endpoints and the
   real-token E2E before announcing availability.

The image includes `/usr/share/licenses/linear/LICENSE` and OCI labels for
source, version, revision, and Apache-2.0 licensing. Check them with
`docker image inspect ghcr.io/hden/linear:0.1.0`.
