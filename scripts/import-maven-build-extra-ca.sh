#!/bin/sh
set -eu

bundle=${1:?CA bundle path is required}
truststore=${2:?temporary truststore path is required}

test -s "$bundle"
cp "$JAVA_HOME/lib/security/cacerts" "$truststore"

certificate_directory=$(mktemp -d)
cleanup() {
    rm -rf "$certificate_directory"
}
trap cleanup EXIT HUP INT TERM

awk -v output_directory="$certificate_directory" '
    /-----BEGIN CERTIFICATE-----/ {
        certificate_count += 1
        output_file = sprintf("%s/certificate-%04d.pem", output_directory, certificate_count)
    }
    output_file != "" {
        print >> output_file
    }
    /-----END CERTIFICATE-----/ {
        close(output_file)
        output_file = ""
    }
    END {
        if (certificate_count == 0 || output_file != "") {
            exit 42
        }
    }
' "$bundle"

certificate_count=0
for certificate in "$certificate_directory"/certificate-*.pem; do
    test -f "$certificate"
    certificate_count=$((certificate_count + 1))
    keytool -importcert -noprompt -trustcacerts \
        -keystore "$truststore" \
        -storepass changeit \
        -alias "axms-host-extra-ca-$certificate_count" \
        -file "$certificate" >/dev/null 2>&1
done

test "$certificate_count" -gt 0
