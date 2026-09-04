package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A patch is two claims at once: what the change is, and what the surrounding file looks
 * like. Only the first belongs to the model. Measured on Jobs a15a51b1 and 0c78172c, the
 * second is what it cannot deliver - src/features/site/PublicSite.tsx carries a line of
 * 1,832 characters, and changing ten of them by unified diff means transcribing the other
 * 1,822 twice, exactly. Fifteen patches in a row died there, git refusing each with
 * "corrupt patch", and the model's own summary was that the file "would require direct
 * modification".
 *
 * <p>So the model names the text to replace and the server builds the diff around it. The
 * line numbers, the counts and every context line are then copied from the file the
 * workspace just returned rather than remembered, and git still checks all of it against
 * the real bytes before applying anything.
 */
class CodingToolReplacementPatchTest {

    private static final String FILE = """
            import { useState } from "react";

            export default function PublicSite() {
              const [open, setOpen] = useState(false);
              return (
                <main>
                  <button onClick={() => setOpen(true)}>사업 소개 보기</button>
                </main>
              );
            }
            """;

    @Test
    @DisplayName("A one-line replacement becomes a hunk whose numbers match the real file")
    void buildsAHunkWithHonestNumbers() {
        String updated = FILE.replace("사업 소개 보기", "사업 소개 보기 · 장차윤 ㅋㅋ");

        String patch = CodingToolService.buildReplacementPatch(
                "src/features/site/PublicSite.tsx", FILE, updated);

        // The changed line is the 7th, so three lines of context open the hunk at line 4.
        assertThat(patch).isEqualTo("""
                diff --git a/src/features/site/PublicSite.tsx b/src/features/site/PublicSite.tsx
                --- a/src/features/site/PublicSite.tsx
                +++ b/src/features/site/PublicSite.tsx
                @@ -4,7 +4,7 @@
                   const [open, setOpen] = useState(false);
                   return (
                     <main>
                -      <button onClick={() => setOpen(true)}>사업 소개 보기</button>
                +      <button onClick={() => setOpen(true)}>사업 소개 보기 · 장차윤 ㅋㅋ</button>
                     </main>
                   );
                 }
                """);
    }

    @Test
    @DisplayName("The counts the precheck computes agree with the ones written here")
    void agreesWithTheServerSideCount() {
        String updated = FILE.replace("사업 소개 보기", "장차윤 ㅋㅋ");

        String patch = CodingToolService.buildReplacementPatch("a/b.tsx", FILE, updated);

        // normalizePatchText recounts every hunk body and rewrites any header that lies.
        // Leaving the patch untouched is that independent count agreeing with this one.
        assertThat(CodingToolService.normalizePatchText(patch)).isEqualTo(patch);
    }

    @Test
    @DisplayName("A short edit inside a very long line does not restate the rest of it")
    void editsInsideALongLineWithoutRetypingIt() {
        // The shape that defeated the model: one line far longer than the change in it.
        String padding = "x".repeat(1_800);
        String original = "first\n<div className=\"" + padding + "\">사업 소개 보기</div>\nlast\n";
        String updated = original.replace("사업 소개 보기", "장차윤 ㅋㅋ");

        String patch = CodingToolService.buildReplacementPatch("p.tsx", original, updated);

        assertThat(CodingToolService.normalizePatchText(patch)).isEqualTo(patch);
        assertThat(patch).contains("@@ -1,3 +1,3 @@");
        // The long line is still carried once on each side - that is what a unified diff
        // is - but the model never had to produce either copy.
        assertThat(patch).contains("-<div className=\"" + padding + "\">사업 소개 보기</div>");
        assertThat(patch).contains("+<div className=\"" + padding + "\">장차윤 ㅋㅋ</div>");
    }

    @Test
    @DisplayName("A replacement spanning several lines is one hunk, not one per line")
    void spansSeveralLinesInOneHunk() {
        String updated = FILE.replace("""
                    <main>
                      <button onClick={() => setOpen(true)}>사업 소개 보기</button>
                    </main>
                """, """
                    <main>
                      <button onClick={() => setOpen(true)}>장차윤 ㅋㅋ</button>
                      <p>새 줄</p>
                    </main>
                """);

        String patch = CodingToolService.buildReplacementPatch("p.tsx", FILE, updated);

        assertThat(patch.split("@@ -", -1)).hasSize(2);
        assertThat(CodingToolService.normalizePatchText(patch)).isEqualTo(patch);
        assertThat(patch).contains("+      <p>새 줄</p>");
    }

    @Test
    @DisplayName("A file whose last line has no newline keeps git's marker on the right side")
    void marksAMissingFinalNewline() {
        String original = "alpha\nbeta";
        String updated = "alpha\ngamma";

        String patch = CodingToolService.buildReplacementPatch("p.txt", original, updated);

        assertThat(patch).isEqualTo("""
                diff --git a/p.txt b/p.txt
                --- a/p.txt
                +++ b/p.txt
                @@ -1,2 +1,2 @@
                 alpha
                -beta
                \\ No newline at end of file
                +gamma
                \\ No newline at end of file
                """);
    }

    @Test
    @DisplayName("Deleting text leaves a hunk that only removes lines")
    void deletesLines() {
        String original = "one\ntwo\nthree\n";
        String updated = "one\nthree\n";

        String patch = CodingToolService.buildReplacementPatch("p.txt", original, updated);

        assertThat(patch).isEqualTo("""
                diff --git a/p.txt b/p.txt
                --- a/p.txt
                +++ b/p.txt
                @@ -1,3 +1,2 @@
                 one
                -two
                 three
                """);
        assertThat(CodingToolService.normalizePatchText(patch)).isEqualTo(patch);
    }

    @Test
    @DisplayName("A change past the third line keeps exactly three lines of context")
    void keepsThreeLinesOfContextOnEachSide() {
        String original = "1\n2\n3\n4\n5\n6\n7\n8\n9\n";
        String updated = original.replace("5\n", "five\n");

        String patch = CodingToolService.buildReplacementPatch("p.txt", original, updated);

        assertThat(patch).isEqualTo("""
                diff --git a/p.txt b/p.txt
                --- a/p.txt
                +++ b/p.txt
                @@ -2,7 +2,7 @@
                 2
                 3
                 4
                -5
                +five
                 6
                 7
                 8
                """);
    }
}
