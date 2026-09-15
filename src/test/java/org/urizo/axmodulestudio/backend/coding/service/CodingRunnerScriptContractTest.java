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
                // A child powershell.exe takes the deploy as an argument array (AI04-022), so the
                // fixed target is read from the service map and the array, not one command line.
                .contains("$service = @{ backend = 'spring-app'; frontend = 'frontend' }")
                .contains("'-Service', $service, '-Profile', 'full'")
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
                .contains("'-SourceRoot', \"`\"$sourceRoot`\"\"")
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

    @Test
    void searchesQuotedTextOnlyInsideTheSentFoldersWithoutAShellAndWithinBounds()
            throws Exception {
        String script = Files.readString(Path.of("scripts", "runner.ps1"), StandardCharsets.UTF_8);
        String search = script.substring(
                script.indexOf("function Get-ScanPhraseMatches"),
                script.indexOf("function Add-ScanPhraseMatches"));
        String scan = script.substring(
                script.indexOf("function Invoke-PrepareScanWorktree"),
                script.indexOf("function Get-PreviewArguments"));

        // The request's text never reaches a command line: git reads it from a file as fixed
        // strings, and only the folders the Backend sent follow the pathspec separator.
        assertThat(search)
                .contains("grep -n -I -F --no-color -f `\"$patternFile`\" -- $pathspec")
                .contains("[IO.Path]::GetTempFileName()")
                .contains("$start.UseShellExecute = $false")
                .contains("[Text.Encoding]::UTF8.GetString($buffer, 0, $filled)")
                .contains("$read.Wait([int]$wait)")
                .contains("$process.WaitForExit($left)")
                .contains("$process.Kill()")
                .contains("[int]$PerPhraseMilliseconds = 2000")
                .contains("[int]$TotalMilliseconds = 6000")
                .contains("[int]$MaxLinesPerPhrase = 20")
                .contains("[int]$MaxBytesPerPhrase = 65536")
                .doesNotContain("Invoke-Expression")
                .doesNotContain("cmd.exe")
                .doesNotContain("& git");
        // Every result the scan returns - fresh, reused, or kept dirty - carries the search.
        assertThat(scan.split("Add-ScanPhraseMatches", -1)).hasSize(4);
    }
}
