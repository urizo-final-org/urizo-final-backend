package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class CodingRunnerScriptContractTest {

    @Test
    void keepsGithubAndDeploymentExecutionInsideFixedHostRunnerCommands() throws Exception {
        String script = Files.readString(Path.of("scripts", "runner.ps1"), StandardCharsets.UTF_8);

        assertThat(script)
                .contains("function Get-ExactPullRequest")
                .contains("--base dev --head $Branch --state all")
                .contains("Export-McpWorkspaceToHost")
                .contains("GIT_AUTHOR_DATE = '2000-01-01T00:00:00Z'")
                .contains("commit --no-gpg-sign --no-verify")
                .contains("function Invoke-CheckDevMerge")
                .contains("'CHECK_DEV_MERGE'")
                .contains("function Invoke-LocalDockerComposeDeployment")
                .contains("-Service spring-app -Profile full")
                .contains("'DEPLOY_LOCAL_COMPOSE'")
                .doesNotContain("Invoke-Expression")
                .doesNotContain("deployedPort");
    }

    @Test
    void verifiesApprovedDiffBeforeCommitAndDeploysOnlyTheMergedDevCommit()
            throws Exception {
        String script = Files.readString(Path.of("scripts", "runner.ps1"), StandardCharsets.UTF_8);
        int digestCheck = script.indexOf("$actualDiffDigest -ne $expectedDiffDigest");
        int commit = script.indexOf("commit --no-gpg-sign --no-verify");
        String deployWorktree = script.substring(
                script.indexOf("function Get-MergedDeployWorktree"),
                script.indexOf("function Invoke-LocalDockerComposeDeployment"));

        assertThat(script)
                .contains("diff --cached --no-ext-diff --no-textconv --no-color --text HEAD --")
                .contains("RUNNER_PR_SUBJECT_BLOCKED|staged Diff")
                .contains("fetch origin dev:refs/remotes/origin/dev")
                .contains("merge-base --is-ancestor $rawMergeSha origin/dev")
                .contains("worktree add --detach $target $rawMergeSha")
                .contains("-SourceRoot $sourceRoot")
                .contains("RUNNER_GITHUB_TRANSIENT|push")
                .contains("RUNNER_DEPLOY_TRANSIENT|origin/dev fetch");
        assertThat(digestCheck).isGreaterThan(0).isLessThan(commit);
        assertThat(deployWorktree)
                .doesNotContain("Get-AiWorktreePath")
                .doesNotContain("Remove-Item");
    }
}
