package org.urizo.axmodulestudio.backend.cms.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.*;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.*;
import org.urizo.axmodulestudio.backend.cms.repository.CmsRepository;

class CmsBoardContentServiceTest {
    private final CmsRepository repository = mock(CmsRepository.class);
    private final CmsCodeService codes = mock(CmsCodeService.class);
    private final CmsService cms = new CmsService(repository, codes);
    private final BoardView board = new BoardView(4, "여행 소식", "", Instant.EPOCH, Instant.EPOCH, "CARD", "REGION", "CATEGORY");
    private final String document = ContentBody.toDocument("## 안내\n\n- 준비물");
    private final UUID author = UUID.randomUUID();

    @Test void restartDoesNotRecreateOrRemapAnAlreadyAuthoredCms() {
        when(repository.hasContentOrBoards()).thenReturn(true);
        cms.ensureDemoData();
        verify(repository, never()).findSuperAdminAuthor();
        verify(repository, never()).insertContent(any(), any(), any());
        verify(repository, never()).mapMenu(any(), any(), anyLong());
    }

    @Test void legacyPostsAreDocumentsWithoutMutatingStorage() {
        var original = new PostView(12, 4, author, "관리자", "안내", "## 이전 글", Instant.EPOCH, Instant.EPOCH);
        when(repository.findPost(12)).thenReturn(Optional.of(original));
        assertThat(cms.post(12).body()).isEqualTo(ContentBody.toDocument("## 이전 글"));
        verify(repository, never()).updatePost(anyLong(), any(), any());
    }

    @Test void savesEditorDocumentAndSeparateThumbnailAndClassifications() {
        when(repository.findBoard(4)).thenReturn(Optional.of(board));
        when(repository.contentImageExists(7)).thenReturn(true);
        when(repository.insertPost(author, 4, "안내", document)).thenReturn(12L);
        when(repository.findPost(12)).thenReturn(Optional.of(new PostView(12, 4, author, "관리자", "안내", document,
                Instant.EPOCH, Instant.EPOCH, 7L, "숲길", 1L, 2L)));
        var request = new PostRequest("안내", document, 7L, "숲길", 1L, 2L);
        assertThat(cms.createPost(author, 4, request).thumbnailImageId()).isEqualTo(7);
        verify(codes).validateCode(1L, "REGION", null);
        verify(codes).validateCode(2L, "CATEGORY", null);
        verify(repository).updatePostOptions(12, 7L, "숲길", 1L, 2L);
    }

    @Test void rejectsPlainTextAtTheNewEditorSaveBoundary() {
        when(repository.findBoard(4)).thenReturn(Optional.of(board));
        assertThatThrownBy(() -> cms.createPost(author, 4, new PostRequest("제목", "plain text", null, "", null, null)))
                .isInstanceOf(CmsServiceException.class);
        verify(repository, never()).insertPost(any(), anyLong(), any(), any());
    }

    @Test void rejectsMissingThumbnailBeforeInsert() {
        when(repository.findBoard(4)).thenReturn(Optional.of(board));
        assertThatThrownBy(() -> cms.createPost(author, 4, new PostRequest("제목", document, 99L, "", null, null)))
                .isInstanceOf(CmsServiceException.class).hasMessageContaining("대표 이미지");
        verify(repository, never()).insertPost(any(), anyLong(), any(), any());
    }

    @Test void rejectsUnstoredBodyImageBeforeInsert() {
        when(repository.findBoard(4)).thenReturn(Optional.of(board));
        String image = "{\"type\":\"doc\",\"content\":[{\"type\":\"image\",\"attrs\":{\"src\":\"/api/site/images/99\",\"alt\":\"숲길\"}}]}";
        assertThatThrownBy(() -> cms.createPost(author, 4, new PostRequest("제목", image, null, "", null, null)))
                .isInstanceOf(CmsServiceException.class).hasMessageContaining("본문 이미지");
        verify(repository, never()).insertPost(any(), anyLong(), any(), any());
    }

    @Test void refusesGroupSwitchWhilePostsStillUseItsCodes() {
        when(repository.findBoard(4)).thenReturn(Optional.of(board));
        when(repository.findPosts(4)).thenReturn(List.of(new PostView(12, 4, author, "관리자", "제목", document,
                Instant.EPOCH, Instant.EPOCH, null, "", 1L, null)));
        assertThatThrownBy(() -> cms.updateBoard(4, new BoardRequest("여행 소식", "", "CARD", "OTHER", "CATEGORY")))
                .isInstanceOf(CmsServiceException.class).hasMessageContaining("먼저 해제");
        verify(repository, never()).updateBoard(anyLong(), any(), any());
    }

    @Test void rejectsUnsupportedBoardDisplayType() {
        assertThatThrownBy(() -> cms.createBoard(new BoardRequest("새 게시판", "", "CUSTOM", null, null)))
                .isInstanceOf(CmsServiceException.class);
        verify(repository, never()).insertBoard(any(), any());
    }
}
