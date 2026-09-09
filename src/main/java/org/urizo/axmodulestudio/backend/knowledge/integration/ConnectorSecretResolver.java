package org.urizo.axmodulestudio.backend.knowledge.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 커넥터 {@code authentication.secretRef}를 실제 인증 값으로 바꾼다.
 *
 * <p>커넥터 설정에는 키 값이 아니라 참조만 저장된다. 실제 값은 이 저장소가 이미 쓰고 있는
 * 파일 기반 Secret 방식(DB 비밀번호·JWT 서명키와 같은 {@code /run/secrets})에서 읽는다.
 * 새 보관 방식을 만들지 않는다.
 *
 * <p>파일 이름에 {@code connector_} 접두사를 붙이는 것은 보기 좋으라고가 아니라 격리다.
 * 접두사가 없으면 {@code cms-secret://cms-app-password} 같은 참조가 DB 비밀번호 파일을
 * 그대로 읽어낸다. 커넥터는 커넥터 몫의 Secret만 볼 수 있어야 한다.
 */
@Component
@Profile("local-full")
public class ConnectorSecretResolver {

    private static final String SCHEME = "cms-secret://";

    /** 경로 조작을 막는다. 점·슬래시·상위 경로가 이름에 들어올 수 없다. */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private final Path directory;

    ConnectorSecretResolver(
            @Value("${ax.knowledge.connector.secrets-dir:/run/secrets}") String directory) {
        this.directory = Path.of(directory);
    }

    public String resolve(String secretRef) {
        if (secretRef == null || !secretRef.startsWith(SCHEME)) {
            throw new IllegalStateException(
                    "Connector authentication requires a " + SCHEME + " reference.");
        }
        String name = secretRef.substring(SCHEME.length());
        if (!NAME.matcher(name).matches()) {
            throw new IllegalStateException("Connector secret reference name is invalid.");
        }
        Path file = directory.resolve("connector_" + name.replace('-', '_'));
        String value;
        try {
            value = Files.readString(file, StandardCharsets.UTF_8).trim();
        }
        catch (IOException failure) {
            // 값이 아니라 참조만 남긴다. 예외 메시지는 로그로 흘러간다.
            throw new IllegalStateException(
                    "Connector secret is not available for " + secretRef + ".", failure);
        }
        if (value.isEmpty()) {
            throw new IllegalStateException("Connector secret is empty for " + secretRef + ".");
        }
        return value;
    }
}
