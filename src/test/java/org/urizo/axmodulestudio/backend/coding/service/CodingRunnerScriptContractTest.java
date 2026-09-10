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
        String githubApp = Files.readString(
                Path.of("scripts", "github-app-pr.ps1"), StandardCharsets.UTF_8);

        assertThat(script)
                .contains("Export-McpWorkspaceToHost")
                .contains("New-AxmsGitHubAppSession")
                .contains("credential.helper=!gh auth git-credential")
                .contains("push $pushUrl \"HEAD`:refs/heads/$branch\"")
                .contains("--repo \"github.com/$slug\"")
                .contains("--body-file $bodyFile")
                .contains("GIT_AUTHOR_DATE = '2000-01-01T00:00:00Z'")
                .contains("commit --no-gpg-sign --no-verify")
                .contains("function Invoke-CheckDevMerge")
                .contains("'CHECK_DEV_MERGE'")
                .contains("function Invoke-LocalDockerComposeDeployment")
                .contains("-Service spring-app -Profile full")
                .contains("-Service frontend -Profile full")
                .contains("'DEPLOY_LOCAL_COMPOSE'")
                .doesNotContain("Invoke-Expression")
                .doesNotContain("deployedPort");
        assertThat(githubApp)
                .contains("function Get-ExactPullRequest")
                .contains("--base dev --head $Branch --state all")
                .contains("$handler.AllowAutoRedirect = $false")
                .contains("contents = 'write'")
                .contains("pull_requests = 'write'")
                .contains("metadata = 'read'")
                .doesNotContain("Invoke-Expression");
    }

    @Test
    void verifiesApprovedDiffBeforeCommitAndDeploysOnlyTheMergedDevCommit()
            throws Exception {
        String script = Files.readString(Path.of("scripts", "runner.ps1"), StandardCharsets.UTF_8);
        String githubApp = Files.readString(
                Path.of("scripts", "github-app-pr.ps1"), StandardCharsets.UTF_8);
        int subjectCheck = script.indexOf("$slug = Assert-AxmsGitHubPrWorkspaceBinding");
        int appSession = script.indexOf("$appSession = New-AxmsGitHubAppSession");
        int commit = script.indexOf("commit --no-gpg-sign --no-verify");
        String deployWorktree = script.substring(
                script.indexOf("function Get-MergedDeployWorktree"),
                script.indexOf("function Invoke-LocalDockerComposeDeployment"));

        assertThat(script)
                .contains("diff --cached --no-ext-diff --no-textconv --no-color --text HEAD --")
                .contains("fetch origin dev:refs/remotes/origin/dev")
                .contains("merge-base --is-ancestor $rawMergeSha origin/dev")
                .contains("worktree add --detach $target $rawMergeSha")
                .contains("-SourceRoot $sourceRoot")
                .contains("RUNNER_GITHUB_TRANSIENT|push")
                .contains("RUNNER_DEPLOY_TRANSIENT|origin/dev fetch");
        assertThat(githubApp).contains("RUNNER_PR_SUBJECT_BLOCKED|staged Diff");
        assertThat(subjectCheck).isGreaterThan(0).isLessThan(appSession);
        assertThat(appSession).isLessThan(commit);
        assertThat(deployWorktree)
                .doesNotContain("Get-AiWorktreePath")
                .doesNotContain("Remove-Item");
    }

    @Test
    void preparesCanonicalFrontendForBackendBuildAndPreview() throws Exception {
        String script = Files.readString(Path.of("scripts", "runner.ps1"), StandardCharsets.UTF_8);

        assertThat(script)
                .contains("else { Get-RepositorySourcePath -Repository 'frontend' }")
                .contains("RUNNER_REPOSITORY_MISSING|Frontend 미리보기 Source가 없습니다")
                .contains("'backend' { @('spring-app', 'flyway-migration', 'frontend') }")
                .contains("$frontendWorktree = Get-RepositorySourcePath -Repository 'frontend'")
                .contains("Set-PreviewEnvironment -FrontendSource $frontendWorktree")
                .doesNotContain("Join-Path $WorkRoot 'ai-frontend'")
                .doesNotContain("$frontendWorktree = ''");
    }
}
