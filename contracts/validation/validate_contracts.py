#!/usr/bin/env python3
"""Dependency-free Stage 0 validation for AX Module Studio contracts."""

from __future__ import annotations

import json
import hashlib
import re
import sys
import uuid
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import unquote_to_bytes, urlsplit


CONTRACT_ROOT = Path(__file__).resolve().parents[1]
FIXTURE_ROOT = CONTRACT_ROOT / "fixtures"
OPENAPI_VERSION = "3.1.0"
JSON_SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema"
PAYLOAD_VERSION = "1.0"

ONE_OF_REF_UNIONS: dict[tuple[str, str], tuple[str, ...]] = {
    ("coding-agent/job-event.schema.json", "#"): (
        "#/$defs/CodingJobRequested",
        "#/$defs/ToolExecutionCompleted",
        "#/$defs/ApprovalRecorded",
    ),
    ("coding-agent/job-event.schema.json", "#/$defs/ToolCompletionPayload"): (
        "#/$defs/SuccessfulToolCompletion",
        "#/$defs/UnsuccessfulToolCompletion",
    ),
    ("coding-agent/job-event.schema.json", "#/$defs/UnsuccessfulToolCompletion"): (
        "#/$defs/FailedToolCompletion",
        "#/$defs/DeniedToolCompletion",
        "#/$defs/TimedOutToolCompletion",
    ),
    ("coding-agent/tool-request.schema.json", "#"): (
        "#/$defs/ToolRequest",
        "#/$defs/ToolAccepted",
        "#/$defs/ToolSucceeded",
        "#/$defs/ToolUnsuccessful",
        "#/$defs/ToolTimedOut",
    ),
    ("coding-agent/tool-request.schema.json", "#/$defs/ToolCandidate"): (
        "#/$defs/ReadFileTool",
        "#/$defs/SearchCodeTool",
        "#/$defs/ApplyPatchTool",
        "#/$defs/RunCheckTool",
    ),
    ("coding-agent/tool-request.schema.json", "#/$defs/ToolUnsuccessful"): (
        "#/$defs/ToolDenied",
        "#/$defs/ToolFailed",
    ),
    ("coding-agent/tool-request.schema.json", "#/$defs/ToolExecutionStatus"): (
        "#/$defs/ToolAccepted",
        "#/$defs/ToolTerminalResult",
    ),
    ("coding-agent/tool-request.schema.json", "#/$defs/ToolTerminalResult"): (
        "#/$defs/ToolSucceeded",
        "#/$defs/ToolUnsuccessful",
        "#/$defs/ToolTimedOut",
    ),
    ("coding-agent/error-code.schema.json", "#/$defs/Error"): (
        "#/$defs/NonRetryableError",
        "#/$defs/RetryableError",
        "#/$defs/AmbiguousToolTimeoutError",
    ),
    ("coding-agent/error-code.schema.json", "#/$defs/ErrorEnvelope"): (
        "#/$defs/PreContextErrorEnvelope",
        "#/$defs/JobScopedErrorEnvelope",
        "#/$defs/ExecutionContextErrorEnvelope",
    ),
}


class ContractFailure(Exception):
    """Fatal contract or validation failure."""


class DuplicateKeyFailure(ContractFailure):
    """A JSON object contains a duplicate key."""


@dataclass
class InstanceFailure(Exception):
    keyword: str
    path: str
    message: str

    def __str__(self) -> str:
        return f"{self.path}: {self.keyword}: {self.message}"


def strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyFailure(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def reject_nonfinite_constant(value: str) -> None:
    raise ContractFailure(f"non-standard JSON number is forbidden: {value}")


class ContractStore:
    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.documents: dict[Path, Any] = {}

    def load(self, path: Path) -> Any:
        resolved = path.resolve()
        try:
            resolved.relative_to(self.root)
        except ValueError as exc:
            raise ContractFailure(f"reference escapes contract root: {resolved}") from exc
        if resolved not in self.documents:
            try:
                text = resolved.read_text(encoding="utf-8")
            except OSError as exc:
                raise ContractFailure(f"cannot read {resolved}: {exc}") from exc
            try:
                self.documents[resolved] = json.loads(
                    text,
                    object_pairs_hook=strict_object,
                    parse_constant=reject_nonfinite_constant,
                    parse_float=Decimal,
                )
            except (json.JSONDecodeError, DuplicateKeyFailure, ContractFailure) as exc:
                raise ContractFailure(f"invalid JSON/YAML-JSON document {resolved}: {exc}") from exc
        return self.documents[resolved]

    def pointer(self, document: Any, fragment: str) -> Any:
        if fragment == "":
            return document
        if re.search(r"%(?![0-9A-Fa-f]{2})", fragment):
            raise ContractFailure(f"invalid percent escape in JSON pointer: #{fragment}")
        try:
            fragment = unquote_to_bytes(fragment).decode("utf-8", errors="strict")
        except UnicodeDecodeError as exc:
            raise ContractFailure(f"invalid UTF-8 in JSON pointer: #{fragment}") from exc
        if not fragment.startswith("/"):
            raise ContractFailure(f"unsupported non-JSON-pointer fragment: #{fragment}")
        current = document
        for raw_part in fragment[1:].split("/"):
            if re.search(r"~(?![01])", raw_part):
                raise ContractFailure(f"invalid JSON pointer escape: {raw_part}")
            part = raw_part.replace("~1", "/").replace("~0", "~")
            if isinstance(current, list):
                if re.fullmatch(r"0|[1-9][0-9]*", part) is None:
                    raise ContractFailure(f"invalid array pointer segment: {part}")
                try:
                    current = current[int(part)]
                except IndexError as exc:
                    raise ContractFailure(f"unresolved array pointer segment: {part}") from exc
            elif isinstance(current, dict) and part in current:
                current = current[part]
            else:
                raise ContractFailure(f"unresolved object pointer segment: {part}")
        return current

    def resolve_ref(self, current_path: Path, ref: str) -> tuple[Any, Path]:
        if not isinstance(ref, str) or not ref:
            raise ContractFailure("$ref must be a non-empty string")
        file_part, separator, fragment = ref.partition("#")
        if file_part:
            if re.fullmatch(r"[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*", file_part) is None:
                raise ContractFailure(
                    f"non-portable local $ref path; use a relative POSIX path: {ref}"
                )
            if any(segment in (".", "..") for segment in file_part.split("/")):
                raise ContractFailure(f"dot segments are forbidden in local $ref paths: {ref}")
        target_path = (
            (current_path.parent / file_part).resolve() if file_part else current_path.resolve()
        )
        target_document = self.load(target_path)
        target = self.pointer(target_document, fragment if separator else "")
        return target, target_path


STORE = ContractStore(CONTRACT_ROOT)


def iter_nodes(value: Any) -> Iterable[Any]:
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from iter_nodes(child)
    elif isinstance(value, list):
        for child in value:
            yield from iter_nodes(child)


def iter_nodes_with_pointer(value: Any, pointer: str = "") -> Iterable[tuple[str, Any]]:
    yield pointer, value
    if isinstance(value, dict):
        for key, child in value.items():
            yield from iter_nodes_with_pointer(child, child_path(pointer, key))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from iter_nodes_with_pointer(child, child_path(pointer, index))


def escape_path(value: str) -> str:
    return value.replace("~", "~0").replace("/", "~1")


def child_path(path: str, child: str | int) -> str:
    return f"{path}/{escape_path(str(child))}" if path else f"/{escape_path(str(child))}"


def type_matches(instance: Any, expected: str) -> bool:
    if expected == "null":
        return instance is None
    if expected == "boolean":
        return isinstance(instance, bool)
    if expected == "integer":
        return (
            isinstance(instance, int)
            and not isinstance(instance, bool)
        ) or (
            isinstance(instance, Decimal)
            and instance.is_finite()
            and instance == instance.to_integral_value()
        )
    if expected == "number":
        return isinstance(instance, (int, Decimal)) and not isinstance(instance, bool)
    if expected == "string":
        return isinstance(instance, str)
    if expected == "array":
        return isinstance(instance, list)
    if expected == "object":
        return isinstance(instance, dict)
    raise ContractFailure(f"unsupported schema type in local profile: {expected}")


RFC3339_PATTERN = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}"
    r"(?:\.[0-9]+)?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})$"
)
INVALID_URI_CHARACTER = re.compile(r"[\x00-\x20\x7f]")
INVALID_PERCENT_ESCAPE = re.compile(r"%(?![0-9A-Fa-f]{2})")


def validate_format(value: str, format_name: str, path: str) -> None:
    if format_name == "uuid":
        try:
            parsed = uuid.UUID(value)
        except (ValueError, AttributeError) as exc:
            raise InstanceFailure("format", path, "must be an RFC 4122 UUID") from exc
        if str(parsed) != value.lower():
            raise InstanceFailure("format", path, "must use canonical hyphenated UUID form")
    elif format_name == "date-time":
        if RFC3339_PATTERN.fullmatch(value) is None:
            raise InstanceFailure("format", path, "must use RFC 3339 full-date and full-time syntax")
        try:
            normalized = value.replace("t", "T").replace("z", "+00:00").replace("Z", "+00:00")
            parsed_time = datetime.fromisoformat(normalized)
        except (ValueError, AttributeError) as exc:
            raise InstanceFailure("format", path, "must be an RFC 3339 date-time") from exc
        if parsed_time.tzinfo is None:
            raise InstanceFailure("format", path, "date-time must include an offset")
    elif format_name == "uri":
        if (
            INVALID_URI_CHARACTER.search(value)
            or INVALID_PERCENT_ESCAPE.search(value)
            or "\\" in value
            or any(ord(character) > 127 for character in value)
        ):
            raise InstanceFailure("format", path, "must not contain whitespace, controls, or bad escapes")
        try:
            parts = urlsplit(value)
            _ = parts.hostname
            _ = parts.port
        except ValueError as exc:
            raise InstanceFailure("format", path, "must be a well-formed absolute URI") from exc
        if not parts.scheme or (parts.scheme in ("http", "https") and not parts.netloc):
            raise InstanceFailure("format", path, "must be an absolute URI")
    elif format_name == "uri-reference":
        if (
            INVALID_URI_CHARACTER.search(value)
            or INVALID_PERCENT_ESCAPE.search(value)
            or "\\" in value
            or any(ord(character) > 127 for character in value)
        ):
            raise InstanceFailure("format", path, "must be a URI reference")
    else:
        raise ContractFailure(f"unsupported format in local profile: {format_name}")


def validate_instance(
    instance: Any,
    schema: Any,
    schema_path: Path,
    instance_path: str = "",
) -> None:
    if isinstance(schema, bool):
        if not schema:
            raise InstanceFailure("falseSchema", instance_path, "schema rejects every value")
        return
    if not isinstance(schema, dict):
        raise ContractFailure(f"schema is not an object at {schema_path}")

    if "$ref" in schema:
        target, target_path = STORE.resolve_ref(schema_path, schema["$ref"])
        validate_instance(instance, target, target_path, instance_path)

    if "allOf" in schema:
        for branch in schema["allOf"]:
            validate_instance(instance, branch, schema_path, instance_path)
    if "if" in schema:
        condition_matches = True
        try:
            validate_instance(instance, schema["if"], schema_path, instance_path)
        except InstanceFailure:
            condition_matches = False
        if condition_matches and "then" in schema:
            validate_instance(instance, schema["then"], schema_path, instance_path)
        if not condition_matches and "else" in schema:
            validate_instance(instance, schema["else"], schema_path, instance_path)
    if "anyOf" in schema:
        matches = 0
        for branch in schema["anyOf"]:
            try:
                validate_instance(instance, branch, schema_path, instance_path)
                matches += 1
            except InstanceFailure:
                pass
        if matches == 0:
            raise InstanceFailure("anyOf", instance_path, "must match at least one branch")
    if "oneOf" in schema:
        matches = 0
        for branch in schema["oneOf"]:
            try:
                validate_instance(instance, branch, schema_path, instance_path)
                matches += 1
            except InstanceFailure:
                pass
        if matches != 1:
            raise InstanceFailure("oneOf", instance_path, f"matched {matches} branches, expected exactly one")
    if "not" in schema:
        try:
            validate_instance(instance, schema["not"], schema_path, instance_path)
        except InstanceFailure:
            pass
        else:
            raise InstanceFailure("not", instance_path, "matches a forbidden schema")

    if "const" in schema and not json_semantic_equal(instance, schema["const"]):
        raise InstanceFailure("const", instance_path, f"must equal {schema['const']!r}")
    if "enum" in schema and not any(
        json_semantic_equal(instance, candidate) for candidate in schema["enum"]
    ):
        raise InstanceFailure("enum", instance_path, f"must be one of {schema['enum']!r}")

    if "type" in schema:
        declared = schema["type"]
        declared_types = declared if isinstance(declared, list) else [declared]
        if not any(type_matches(instance, expected) for expected in declared_types):
            raise InstanceFailure("type", instance_path, f"must have type {declared!r}")

    if isinstance(instance, dict):
        required = schema.get("required", [])
        for name in required:
            if name not in instance:
                raise InstanceFailure("required", instance_path, f"missing required property {name!r}")
        if "minProperties" in schema and len(instance) < schema["minProperties"]:
            raise InstanceFailure("minProperties", instance_path, "has too few properties")
        if "maxProperties" in schema and len(instance) > schema["maxProperties"]:
            raise InstanceFailure("maxProperties", instance_path, "has too many properties")
        properties = schema.get("properties", {})
        additional = schema.get("additionalProperties", True)
        for name, value in instance.items():
            value_path = child_path(instance_path, name)
            if name in properties:
                validate_instance(value, properties[name], schema_path, value_path)
            elif additional is False:
                raise InstanceFailure("additionalProperties", value_path, "property is not allowed")
            elif isinstance(additional, dict) or isinstance(additional, bool):
                validate_instance(value, additional, schema_path, value_path)
            else:
                raise ContractFailure(
                    f"additionalProperties must be boolean or schema at {schema_path}"
                )

    if isinstance(instance, list):
        if "minItems" in schema and len(instance) < schema["minItems"]:
            raise InstanceFailure("minItems", instance_path, "has too few items")
        if "maxItems" in schema and len(instance) > schema["maxItems"]:
            raise InstanceFailure("maxItems", instance_path, "has too many items")
        if schema.get("uniqueItems"):
            for left_index, left in enumerate(instance):
                for right in instance[left_index + 1 :]:
                    if json_semantic_equal(left, right):
                        raise InstanceFailure("uniqueItems", instance_path, "contains duplicate items")
        if "contains" in schema:
            match_count = 0
            for value in instance:
                try:
                    validate_instance(value, schema["contains"], schema_path, instance_path)
                    match_count += 1
                except InstanceFailure:
                    pass
            minimum_contains = schema.get("minContains", 1)
            maximum_contains = schema.get("maxContains")
            if match_count < minimum_contains:
                raise InstanceFailure("contains", instance_path, "contains too few matching items")
            if maximum_contains is not None and match_count > maximum_contains:
                raise InstanceFailure("maxContains", instance_path, "contains too many matching items")
        if "items" in schema:
            for index, value in enumerate(instance):
                validate_instance(value, schema["items"], schema_path, child_path(instance_path, index))

    if isinstance(instance, str):
        if "minLength" in schema and len(instance) < schema["minLength"]:
            raise InstanceFailure("minLength", instance_path, "is too short")
        if "maxLength" in schema and len(instance) > schema["maxLength"]:
            raise InstanceFailure("maxLength", instance_path, "is too long")
        if "pattern" in schema:
            try:
                matched = re.search(schema["pattern"], instance)
            except re.error as exc:
                raise ContractFailure(f"invalid regex {schema['pattern']!r}: {exc}") from exc
            if matched is None:
                raise InstanceFailure("pattern", instance_path, f"does not match {schema['pattern']!r}")
        if "format" in schema:
            validate_format(instance, schema["format"], instance_path)

    if isinstance(instance, (int, Decimal)) and not isinstance(instance, bool):
        if "minimum" in schema and instance < schema["minimum"]:
            raise InstanceFailure("minimum", instance_path, "is below the minimum")
        if "maximum" in schema and instance > schema["maximum"]:
            raise InstanceFailure("maximum", instance_path, "is above the maximum")


def json_semantic_equal(left: Any, right: Any) -> bool:
    if isinstance(left, bool) or isinstance(right, bool):
        return isinstance(left, bool) and isinstance(right, bool) and left == right
    if (
        isinstance(left, (int, Decimal))
        and not isinstance(left, bool)
        and isinstance(right, (int, Decimal))
        and not isinstance(right, bool)
    ):
        return left == right
    if left is None or right is None:
        return left is None and right is None
    if isinstance(left, str) or isinstance(right, str):
        return isinstance(left, str) and isinstance(right, str) and left == right
    if isinstance(left, list) or isinstance(right, list):
        return (
            isinstance(left, list)
            and isinstance(right, list)
            and len(left) == len(right)
            and all(json_semantic_equal(a, b) for a, b in zip(left, right))
        )
    if isinstance(left, dict) or isinstance(right, dict):
        return (
            isinstance(left, dict)
            and isinstance(right, dict)
            and left.keys() == right.keys()
            and all(json_semantic_equal(left[key], right[key]) for key in left)
        )
    return left == right


def jcs_serialize(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        if abs(value) > 9007199254740991:
            raise ContractFailure("JCS integer exceeds the I-JSON safe integer range")
        return str(value)
    if isinstance(value, Decimal):
        if not value.is_finite() or value != value.to_integral_value():
            raise ContractFailure(
                "Stage 0 digest-bound schemas allow integer numeric literals only"
            )
        if abs(value) > Decimal(9007199254740991):
            raise ContractFailure("JCS integer exceeds the I-JSON safe integer range")
        return str(int(value))
    if isinstance(value, str):
        try:
            value.encode("utf-8", errors="strict")
        except UnicodeEncodeError as exc:
            raise ContractFailure("JCS input must be valid I-JSON Unicode") from exc
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, list):
        return "[" + ",".join(jcs_serialize(item) for item in value) + "]"
    if isinstance(value, dict):
        if not all(isinstance(key, str) for key in value):
            raise ContractFailure("JCS object keys must be strings")
        ordered_keys = sorted(
            value,
            key=lambda key: key.encode("utf-16-be", errors="surrogatepass"),
        )
        return "{" + ",".join(
            jcs_serialize(key) + ":" + jcs_serialize(value[key])
            for key in ordered_keys
        ) + "}"
    raise ContractFailure(f"unsupported JCS value type: {type(value).__name__}")


def jcs_sha256(value: Any) -> str:
    canonical = jcs_serialize(value).encode("utf-8", errors="strict")
    return "sha256:" + hashlib.sha256(canonical).hexdigest()


EMBEDDED_SCHEMA_KEYWORDS = {
    "$schema", "$id", "title", "description", "default", "examples",
    "type", "const", "enum", "required", "properties", "additionalProperties",
    "minProperties", "maxProperties", "items", "minItems", "maxItems",
    "uniqueItems", "contains", "minContains", "maxContains", "minLength",
    "maxLength", "pattern", "format", "minimum", "maximum", "oneOf",
    "anyOf", "allOf", "not", "if", "then", "else",
}
EMBEDDED_SINGLE_SUBSCHEMA_KEYWORDS = {
    "additionalProperties", "items", "contains", "not", "if", "then", "else"
}
EMBEDDED_ARRAY_SUBSCHEMA_KEYWORDS = {"oneOf", "anyOf", "allOf"}


def validate_embedded_schema_profile(schema: Any, path: str) -> None:
    if isinstance(schema, bool):
        return
    if not isinstance(schema, dict):
        raise InstanceFailure("schemaProfile", path, "embedded schema must be an object or boolean")
    unknown_keywords = set(schema) - EMBEDDED_SCHEMA_KEYWORDS
    if unknown_keywords:
        raise InstanceFailure(
            "schemaProfile",
            path,
            f"unsupported embedded schema keywords: {sorted(unknown_keywords)}",
        )
    for keyword in ("$schema", "$id", "title", "description"):
        if keyword in schema and not isinstance(schema[keyword], str):
            raise InstanceFailure(
                "schemaProfile", child_path(path, keyword), f"{keyword} must be a string"
            )
    if "$schema" in schema and schema["$schema"] != JSON_SCHEMA_DIALECT:
        raise InstanceFailure(
            "schemaProfile", child_path(path, "$schema"), "unsupported schema dialect"
        )
    if "$id" in schema and not urlsplit(schema["$id"]).scheme:
        raise InstanceFailure(
            "schemaProfile", child_path(path, "$id"), "$id must be absolute"
        )
    if "examples" in schema and not isinstance(schema["examples"], list):
        raise InstanceFailure(
            "schemaProfile", child_path(path, "examples"), "examples must be an array"
        )
    allowed_types = {"null", "boolean", "integer", "number", "string", "array", "object"}
    if "type" in schema:
        declared_type = schema["type"]
        declared_types = declared_type if isinstance(declared_type, list) else [declared_type]
        if (
            not declared_types
            or len(declared_types) != len(set(declared_types))
            or any(item not in allowed_types for item in declared_types)
        ):
            raise InstanceFailure(
                "schemaProfile", child_path(path, "type"), "type must use unique JSON types"
            )
    if "enum" in schema and (not isinstance(schema["enum"], list) or not schema["enum"]):
        raise InstanceFailure(
            "schemaProfile", child_path(path, "enum"), "enum must be a non-empty array"
        )
    if "required" in schema and (
        not isinstance(schema["required"], list)
        or len(schema["required"]) != len(set(schema["required"]))
        or any(not isinstance(item, str) for item in schema["required"])
    ):
        raise InstanceFailure(
            "schemaProfile", child_path(path, "required"), "required must contain unique strings"
        )
    for keyword in (
        "minProperties", "maxProperties", "minItems", "maxItems", "minContains",
        "maxContains", "minLength", "maxLength",
    ):
        if keyword in schema and (
            not isinstance(schema[keyword], int)
            or isinstance(schema[keyword], bool)
            or schema[keyword] < 0
        ):
            raise InstanceFailure(
                "schemaProfile", child_path(path, keyword), f"{keyword} must be a non-negative integer"
            )
    for minimum_name, maximum_name in (
        ("minProperties", "maxProperties"),
        ("minItems", "maxItems"),
        ("minContains", "maxContains"),
        ("minLength", "maxLength"),
    ):
        if (
            minimum_name in schema
            and maximum_name in schema
            and schema[minimum_name] > schema[maximum_name]
        ):
            raise InstanceFailure(
                "schemaProfile", path, f"{minimum_name} must not exceed {maximum_name}"
            )
    for keyword in ("minimum", "maximum"):
        if keyword in schema and not type_matches(schema[keyword], "number"):
            raise InstanceFailure(
                "schemaProfile", child_path(path, keyword), f"{keyword} must be a number"
            )
    if "minimum" in schema and "maximum" in schema and schema["minimum"] > schema["maximum"]:
        raise InstanceFailure("schemaProfile", path, "minimum must not exceed maximum")
    if "uniqueItems" in schema and not isinstance(schema["uniqueItems"], bool):
        raise InstanceFailure(
            "schemaProfile", child_path(path, "uniqueItems"), "uniqueItems must be boolean"
        )
    if "pattern" in schema:
        if not isinstance(schema["pattern"], str):
            raise InstanceFailure(
                "schemaProfile", child_path(path, "pattern"), "pattern must be a string"
            )
        try:
            re.compile(schema["pattern"])
        except re.error as exc:
            raise InstanceFailure(
                "schemaProfile", child_path(path, "pattern"), "pattern must compile"
            ) from exc
    schema_format = schema.get("format")
    if schema_format is not None and schema_format not in {
        "uuid", "date-time", "uri", "uri-reference"
    }:
        raise InstanceFailure(
            "schemaProfile", child_path(path, "format"), f"unsupported format {schema_format!r}"
        )
    properties = schema.get("properties")
    if properties is not None:
        if not isinstance(properties, dict):
            raise InstanceFailure(
                "schemaProfile", child_path(path, "properties"), "properties must be an object"
            )
        for property_name, property_schema in properties.items():
            validate_embedded_schema_profile(
                property_schema,
                child_path(child_path(path, "properties"), property_name),
            )
    for keyword in EMBEDDED_SINGLE_SUBSCHEMA_KEYWORDS:
        if keyword in schema:
            validate_embedded_schema_profile(schema[keyword], child_path(path, keyword))
    for keyword in EMBEDDED_ARRAY_SUBSCHEMA_KEYWORDS:
        if keyword not in schema:
            continue
        branches = schema[keyword]
        if not isinstance(branches, list) or not branches:
            raise InstanceFailure(
                "schemaProfile", child_path(path, keyword), f"{keyword} must be a non-empty array"
            )
        for index, branch in enumerate(branches):
            validate_embedded_schema_profile(
                branch, child_path(child_path(path, keyword), index)
            )


def validate_model_schema_digests(fixture: Any, instance_prefix: str = "") -> None:
    if not isinstance(fixture, dict) or "requiredCapabilities" not in fixture:
        return
    for index, tool_schema in enumerate(fixture.get("toolSchemas", [])):
        if not isinstance(tool_schema, dict):
            continue
        input_schema = tool_schema.get("inputSchema")
        validate_embedded_schema_profile(
            input_schema, f"{instance_prefix}/toolSchemas/{index}/inputSchema"
        )
        actual = jcs_sha256(input_schema)
        if tool_schema.get("schemaDigest") != actual:
            raise InstanceFailure(
                "schemaDigest",
                f"{instance_prefix}/toolSchemas/{index}/schemaDigest",
                "tool input schema digest does not match RFC 8785 canonical bytes",
            )
    tool_names = [
        schema.get("name")
        for schema in fixture.get("toolSchemas", [])
        if isinstance(schema, dict)
    ]
    if len(tool_names) != len(set(tool_names)):
        raise InstanceFailure(
            "uniqueToolName",
            f"{instance_prefix}/toolSchemas",
            "registered tool names must be unique",
        )
    response_format = fixture.get("responseFormat")
    if isinstance(response_format, dict) and response_format.get("type") == "JSON_SCHEMA":
        output_schema = response_format.get("outputSchema")
        validate_embedded_schema_profile(
            output_schema, f"{instance_prefix}/responseFormat/outputSchema"
        )
        actual = jcs_sha256(output_schema)
        if response_format.get("schemaDigest") != actual:
            raise InstanceFailure(
                "schemaDigest",
                f"{instance_prefix}/responseFormat/schemaDigest",
                "output schema digest does not match RFC 8785 canonical bytes",
            )


def validate_model_message_flow(fixture: Any, instance_prefix: str = "") -> None:
    if not isinstance(fixture, dict) or "messages" not in fixture:
        return
    seen_calls: set[str] = set()
    unresolved_calls: set[str] = set()
    for message_index, message in enumerate(fixture.get("messages", [])):
        if not isinstance(message, dict):
            continue
        if message.get("role") == "assistant" and isinstance(message.get("toolCalls"), list):
            for call_index, call in enumerate(message["toolCalls"]):
                tool_call_id = call.get("toolCallId") if isinstance(call, dict) else None
                if tool_call_id in seen_calls:
                    raise InstanceFailure(
                        "toolCallSequence",
                        f"{instance_prefix}/messages/{message_index}/toolCalls/{call_index}/toolCallId",
                        "assistant toolCallId must be globally unique within the turn history",
                    )
                seen_calls.add(tool_call_id)
                unresolved_calls.add(tool_call_id)
        elif message.get("role") == "tool":
            tool_call_id = message.get("toolCallId")
            if tool_call_id not in unresolved_calls:
                raise InstanceFailure(
                    "toolCallSequence",
                    f"{instance_prefix}/messages/{message_index}/toolCallId",
                    "tool result must reference exactly one preceding unresolved assistant call",
                )
            unresolved_calls.remove(tool_call_id)
    if unresolved_calls:
        raise InstanceFailure(
            "toolCallSequence",
            f"{instance_prefix}/messages",
            "every preceding assistant tool call must have exactly one result before a new model turn",
        )


def validate_model_response_tool_ids(fixture: Any, instance_prefix: str = "") -> None:
    if not isinstance(fixture, dict) or "toolCalls" not in fixture or "assistant" not in fixture:
        return
    identifiers = [
        call.get("toolCallId")
        for call in fixture.get("toolCalls", [])
        if isinstance(call, dict)
    ]
    if len(identifiers) != len(set(identifiers)):
        raise InstanceFailure(
            "uniqueToolCallId",
            f"{instance_prefix}/toolCalls",
            "response toolCallId values must be unique",
        )


def resolve_object(value: Any, document_path: Path) -> tuple[Any, Path]:
    if isinstance(value, dict) and "$ref" in value:
        return STORE.resolve_ref(document_path, value["$ref"])
    return value, document_path


def schema_requires_exact_version(
    schema: Any,
    document_path: Path,
    seen: set[tuple[Path, str]] | None = None,
) -> bool:
    seen = set() if seen is None else seen
    if isinstance(schema, dict) and "$ref" in schema:
        ref = schema["$ref"]
        marker = (document_path.resolve(), ref)
        if marker in seen:
            return False
        seen.add(marker)
        schema, document_path = STORE.resolve_ref(document_path, ref)
    if not isinstance(schema, dict):
        return False
    required = schema.get("required", [])
    version_schema = schema.get("properties", {}).get("schemaVersion")
    if "schemaVersion" in required and version_schema is not None:
        version_schema, _ = resolve_object(version_schema, document_path)
        return isinstance(version_schema, dict) and version_schema.get("const") == PAYLOAD_VERSION
    for keyword in ("oneOf", "anyOf"):
        branches = schema.get(keyword)
        if branches:
            return all(
                schema_requires_exact_version(branch, document_path, set(seen))
                for branch in branches
            )
    branches = schema.get("allOf")
    if branches:
        return any(
            schema_requires_exact_version(branch, document_path, set(seen))
            for branch in branches
        )
    return False


def require_schema_version(schema: Any, document_path: Path, label: str) -> None:
    if not schema_requires_exact_version(schema, document_path):
        raise ContractFailure(f"{label} does not require exact schemaVersion {PAYLOAD_VERSION}")


def require_contract_version(schema: Any, document_path: Path, label: str) -> None:
    schema, schema_path = resolve_object(schema, document_path)
    required = schema.get("required", [])
    version_schema = schema.get("properties", {}).get("contractVersion")
    if "contractVersion" not in required or version_schema is None:
        raise ContractFailure(
            f"{label} does not require exact contractVersion {PAYLOAD_VERSION}"
        )
    version_schema, _ = resolve_object(version_schema, schema_path)
    if version_schema.get("const") != PAYLOAD_VERSION:
        raise ContractFailure(
            f"{label} does not require exact contractVersion {PAYLOAD_VERSION}"
        )


def validate_openapi_profile(
    path: Path,
    expected_prefix: str,
    raw_contract_version_responses: frozenset[tuple[str, str]] = frozenset(),
) -> int:
    document = STORE.load(path)
    if document.get("openapi") != OPENAPI_VERSION:
        raise ContractFailure(f"{path.name}: openapi must be {OPENAPI_VERSION}")
    if document.get("jsonSchemaDialect") != JSON_SCHEMA_DIALECT:
        raise ContractFailure(f"{path.name}: unexpected JSON Schema dialect")
    info = document.get("info", {})
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", str(info.get("version", ""))):
        raise ContractFailure(f"{path.name}: info.version must be SemVer")
    paths = document.get("paths")
    if not isinstance(paths, dict) or not paths:
        raise ContractFailure(f"{path.name}: paths must be a non-empty object")

    operation_ids: set[str] = set()
    operation_count = 0
    for route, path_item in paths.items():
        if route != expected_prefix and not route.startswith(expected_prefix + "/"):
            raise ContractFailure(f"{path.name}: route crosses boundary: {route}")
        if not isinstance(path_item, dict):
            raise ContractFailure(f"{path.name}: path item {route} must be an object")
        path_parameters = path_item.get("parameters", [])
        if not isinstance(path_parameters, list):
            raise ContractFailure(f"{path.name}: path parameters for {route} must be an array")
        placeholders = re.findall(r"\{([^{}]+)\}", route)
        route_without_placeholders = re.sub(r"\{[^{}]+\}", "", route)
        if "{" in route_without_placeholders or "}" in route_without_placeholders:
            raise ContractFailure(f"{path.name}: malformed path template {route}")
        if len(placeholders) != len(set(placeholders)):
            raise ContractFailure(f"{path.name}: duplicate path template variable in {route}")
        for method in ("get", "post", "put", "patch", "delete", "head", "options", "trace"):
            operation = path_item.get(method)
            if operation is None:
                continue
            if not isinstance(operation, dict):
                raise ContractFailure(f"{path.name}: {method.upper()} {route} must be an object")
            operation_count += 1
            operation_id = operation.get("operationId")
            if not operation_id or operation_id in operation_ids:
                raise ContractFailure(f"{path.name}: missing/duplicate operationId {operation_id!r}")
            operation_ids.add(operation_id)

            operation_parameters = operation.get("parameters", [])
            if not isinstance(operation_parameters, list):
                raise ContractFailure(f"{operation_id}: operation parameters must be an array")
            effective_parameters: dict[tuple[str, str], dict[str, Any]] = {}
            for scope_name, parameter_nodes in (
                ("path item", path_parameters),
                ("operation", operation_parameters),
            ):
                local_keys: set[tuple[str, str]] = set()
                for parameter_node in parameter_nodes:
                    parameter, _ = resolve_object(parameter_node, path)
                    if not isinstance(parameter, dict):
                        raise ContractFailure(f"{operation_id}: parameter must be an object")
                    name = parameter.get("name")
                    location = parameter.get("in")
                    if not isinstance(name, str) or not isinstance(location, str):
                        raise ContractFailure(
                            f"{operation_id}: parameter requires string name and in"
                        )
                    key = (name, location)
                    if key in local_keys:
                        raise ContractFailure(
                            f"{operation_id}: duplicate {scope_name} parameter {key}"
                        )
                    local_keys.add(key)
                    if location == "path" and parameter.get("required") is not True:
                        raise ContractFailure(
                            f"{operation_id}: path parameter {name} must be required"
                        )
                    effective_parameters[key] = parameter
            resolved_parameters = list(effective_parameters.values())
            path_parameter_names = {
                parameter["name"]
                for parameter in resolved_parameters
                if parameter.get("in") == "path"
            }
            if path_parameter_names != set(placeholders):
                raise ContractFailure(
                    f"{operation_id}: path template/parameter mismatch "
                    f"template={sorted(set(placeholders))} parameters={sorted(path_parameter_names)}"
                )
            if expected_prefix == "/api":
                found_trace = False
                for parameter in resolved_parameters:
                    if (
                        parameter.get("name") == "X-Trace-Id"
                        and parameter.get("in") == "header"
                        and parameter.get("required", False) is False
                    ):
                        found_trace = True
                if not found_trace:
                    raise ContractFailure(f"{operation_id}: operation lacks optional X-Trace-Id")

            if expected_prefix == "/api" and method == "post":
                found_idempotency = False
                for parameter in resolved_parameters:
                    if (
                        parameter.get("name") == "Idempotency-Key"
                        and parameter.get("in") == "header"
                        and parameter.get("required") is True
                    ):
                        found_idempotency = True
                if not found_idempotency:
                    raise ContractFailure(f"{operation_id}: POST lacks required Idempotency-Key")

            request_body = operation.get("requestBody")
            if request_body:
                request_body, request_path = resolve_object(request_body, path)
                schema = (
                    request_body.get("content", {})
                    .get("application/json", {})
                    .get("schema")
                )
                if schema is not None:
                    require_schema_version(schema, request_path, f"{operation_id} request")

            responses = operation.get("responses", {})
            if not responses:
                raise ContractFailure(f"{operation_id}: responses are required")
            for status, response in responses.items():
                if re.fullmatch(r"default|[1-5][0-9]{2}", status) is None:
                    raise ContractFailure(f"{operation_id}: invalid response status key {status!r}")
                response, response_path = resolve_object(response, path)
                if status == "401":
                    challenge_node = response.get("headers", {}).get("WWW-Authenticate")
                    if challenge_node is None:
                        raise ContractFailure(
                            f"{operation_id} 401: missing WWW-Authenticate Bearer challenge"
                        )
                    challenge, challenge_path = resolve_object(challenge_node, response_path)
                    challenge_schema = challenge.get("schema") if isinstance(challenge, dict) else None
                    if not isinstance(challenge_schema, dict):
                        raise ContractFailure(f"{operation_id} 401: invalid Bearer challenge header")
                    challenge_schema, _ = resolve_object(challenge_schema, challenge_path)
                    if (
                        challenge.get("required") is not True
                        or not isinstance(challenge_schema, dict)
                        or challenge_schema.get("type") != "string"
                        or challenge_schema.get("const") != "Bearer"
                    ):
                        raise ContractFailure(
                            f"{operation_id} 401: challenge must be required canonical Bearer"
                        )
                if expected_prefix == "/api":
                    headers = response.get("headers", {})
                    if "X-Trace-Id" not in headers:
                        raise ContractFailure(f"{operation_id} {status}: missing X-Trace-Id")
                if status == "202" and not {"Location", "Retry-After"}.issubset(
                    response.get("headers", {})
                ):
                    raise ContractFailure(f"{operation_id} 202: missing async headers")
                response_schema = (
                    response.get("content", {})
                    .get("application/json", {})
                    .get("schema")
                )
                if response_schema is not None:
                    label = f"{operation_id} {status} response"
                    if (operation_id, status) in raw_contract_version_responses:
                        require_contract_version(response_schema, response_path, label)
                    else:
                        require_schema_version(response_schema, response_path, label)
    return operation_count


def validate_local_references(paths: Iterable[Path]) -> int:
    count = 0
    for path in paths:
        document = STORE.load(path)
        for node in iter_nodes(document):
            if isinstance(node, dict) and "$ref" in node:
                STORE.resolve_ref(path, node["$ref"])
                count += 1
    return count


OPEN_EXTENSION_ISLANDS = {
    (
        "model-turn.openapi.yaml",
        "/components/schemas/ToolSchema/properties/inputSchema",
    ),
    (
        "model-turn.openapi.yaml",
        "/components/schemas/JsonSchemaResponseFormatRequest/properties/outputSchema",
    ),
    (
        "model-turn.openapi.yaml",
        "/components/schemas/JsonSchemaResponseFormatResult/properties/structuredOutput",
    ),
    (
        "model-turn.openapi.yaml",
        "/components/schemas/ToolCallCandidate/properties/arguments",
    ),
}

CONDITIONAL_OBJECT_APPLICATORS = {"if", "then", "else", "not"}


def validate_schema_profile(paths: Iterable[Path]) -> int:
    strict_objects = 0
    for path in paths:
        document = STORE.load(path)
        if path.name.endswith(".schema.json"):
            if document.get("$schema") != JSON_SCHEMA_DIALECT:
                raise ContractFailure(f"{path.name}: missing Draft 2020-12 $schema")
            schema_id = document.get("$id", "")
            if not urlsplit(schema_id).scheme or "/1.0/" not in schema_id:
                raise ContractFailure(f"{path.name}: $id must be absolute and versioned")
        for pointer, node in iter_nodes_with_pointer(document):
            if not isinstance(node, dict):
                continue
            declared_type = node.get("type")
            is_object_schema = declared_type == "object" or (
                isinstance(declared_type, list) and "object" in declared_type
            )
            uses_object_keywords = (
                "properties" in node
                or "additionalProperties" in node
                or isinstance(node.get("required"), list)
            )
            pointer_segments = [segment for segment in pointer.split("/") if segment]
            schema_container_keywords = {
                "properties", "patternProperties", "dependentSchemas", "$defs", "schemas"
            }
            is_named_conditional_root = bool(
                pointer_segments
                and pointer_segments[-1] in CONDITIONAL_OBJECT_APPLICATORS
                and (
                    len(pointer_segments) == 1
                    or pointer_segments[-2] not in schema_container_keywords
                )
            )
            is_applicator_branch_root = bool(
                len(pointer_segments) >= 2
                and pointer_segments[-1].isdigit()
                and pointer_segments[-2] in {"allOf", "anyOf", "oneOf"}
            )
            has_real_applicator_ancestor = any(
                segment in CONDITIONAL_OBJECT_APPLICATORS
                and (index == 0 or pointer_segments[index - 1] not in schema_container_keywords)
                for index, segment in enumerate(pointer_segments)
            ) or any(segment in {"allOf", "anyOf", "oneOf"} for segment in pointer_segments)
            is_overlay_without_property_policy = (
                has_real_applicator_ancestor and "additionalProperties" not in node
            )
            is_conditional_selector = (
                is_named_conditional_root
                or is_applicator_branch_root
                or is_overlay_without_property_policy
            )
            if uses_object_keywords and not is_object_schema and not is_conditional_selector:
                raise ContractFailure(
                    f"{path.name}{pointer}: object keywords require an explicit object type"
                )
            if is_object_schema:
                strict_objects += 1
                if "additionalProperties" not in node:
                    raise ContractFailure(
                        f"{path.name}: object schema lacks explicit additionalProperties"
                    )
                additional = node["additionalProperties"]
                if not isinstance(additional, (bool, dict)):
                    raise ContractFailure(
                        f"{path.name}{pointer}: additionalProperties must be boolean or schema"
                    )
                if additional is True and (path.name, pointer) not in OPEN_EXTENSION_ISLANDS:
                    raise ContractFailure(
                        f"{path.name}{pointer}: unapproved additionalProperties=true extension island"
                    )
    return strict_objects


SECRET_KEYS = {
    "apikey",
    "api_key",
    "token",
    "password",
    "credential",
    "privatekey",
    "private_key",
    "secretvalue",
    "secret_value",
    "secret",
    "secrets",
    "credentials",
    "passphrase",
    "authorization",
}
SECRET_KEY_SUFFIXES = (
    "apikey",
    "password",
    "accesstoken",
    "refreshtoken",
    "privatekey",
    "secretvalue",
    "credential",
    "secret",
    "secrets",
    "credentials",
    "passphrase",
)
SAFE_SECRET_REFERENCE_KEYS = {"secretref"}
SECRET_VALUE_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16})"),
    re.compile(r"eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}"),
    re.compile(r"AIza[0-9A-Za-z_-]{35}"),
    re.compile(r"(?:lsv2_|sk_live_)[A-Za-z0-9_-]{20,}"),
    re.compile(r"(?:Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{16,}", re.IGNORECASE),
)


def is_secret_shaped_key(key: str) -> bool:
    normalized = key.replace("-", "_").lower()
    collapsed = re.sub(r"[^a-z0-9]", "", normalized)
    return normalized in SECRET_KEYS or (
        collapsed not in SAFE_SECRET_REFERENCE_KEYS
        and collapsed.endswith(SECRET_KEY_SUFFIXES)
    )


def reject_secret_shaped_keys(value: Any, path: str = "") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if is_secret_shaped_key(key):
                raise InstanceFailure(
                    "secretShape",
                    child_path(path, key),
                    "secret-shaped fixture key is forbidden",
                )
            reject_secret_shaped_keys(child, child_path(path, key))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            reject_secret_shaped_keys(child, child_path(path, index))
    elif isinstance(value, str):
        for pattern in SECRET_VALUE_PATTERNS:
            if pattern.search(value):
                raise InstanceFailure(
                    "secretShape",
                    path,
                    "credential-like fixture value is forbidden",
                )


def reject_secret_shaped_values(value: Any, path: str = "") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            reject_secret_shaped_values(child, child_path(path, key))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            reject_secret_shaped_values(child, child_path(path, index))
    elif isinstance(value, str):
        for pattern in SECRET_VALUE_PATTERNS:
            if pattern.search(value):
                raise ContractFailure(f"secret-shaped contract value at {path or '/'}")


def reject_secret_shaped_schema_properties(paths: Iterable[Path]) -> None:
    for path in paths:
        document = STORE.load(path)
        for pointer, node in iter_nodes_with_pointer(document):
            if not isinstance(node, dict) or not isinstance(node.get("properties"), dict):
                continue
            for property_name in node["properties"]:
                if is_secret_shaped_key(property_name):
                    raise ContractFailure(
                        f"{path.name}{pointer}/properties/{property_name}: secret-shaped payload property"
                    )
        for pointer, node in iter_nodes_with_pointer(document):
            if (
                isinstance(node, dict)
                and node.get("in") == "header"
                and isinstance(node.get("name"), str)
                and is_secret_shaped_key(node["name"])
            ):
                raise ContractFailure(
                    f"{path.name}{pointer}: raw credential-shaped request header is forbidden"
                )


def find_error_code(value: Any) -> str | None:
    if isinstance(value, dict):
        error = value.get("error")
        if isinstance(error, dict) and isinstance(error.get("code"), str):
            return error["code"]
        for child in value.values():
            code = find_error_code(child)
            if code is not None:
                return code
    elif isinstance(value, list):
        for child in value:
            code = find_error_code(child)
            if code is not None:
                return code
    return None


def canonical_idempotency_payload(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: child for key, child in value.items() if key != "attempt"}
    return value


def path_within_scope(path: str, scopes: list[Any]) -> bool:
    return any(
        isinstance(scope, str)
        and (path == scope.rstrip("/") or path.startswith(scope.rstrip("/") + "/"))
        for scope in scopes
    )


def unified_diff_targets(patch: str) -> list[str]:
    old_headers: list[str] = []
    new_headers: list[str] = []
    for line in patch.splitlines():
        if line.startswith("--- "):
            old_headers.append(line[4:].split("\t", 1)[0])
        elif line.startswith("+++ "):
            new_headers.append(line[4:].split("\t", 1)[0])
    if not old_headers or len(old_headers) != len(new_headers):
        raise InstanceFailure(
            "patchFormat",
            "/tool/arguments/patch",
            "unified diff must contain paired --- and +++ path headers",
        )
    targets: list[str] = []
    relative_path_schema, relative_schema_path = STORE.resolve_ref(
        CONTRACT_ROOT / "coding-agent" / "tool-request.schema.json",
        "#/$defs/RelativePath",
    )
    for old_header, new_header in zip(old_headers, new_headers):
        if new_header == "/dev/null":
            raise InstanceFailure(
                "pathScope",
                "/tool/arguments/patch",
                "file deletion is not an allowed Stage 0 patch operation",
            )
        real_headers = [header for header in (old_header, new_header) if header != "/dev/null"]
        if not real_headers:
            raise InstanceFailure(
                "patchFormat", "/tool/arguments/patch", "diff has no repository target"
            )
        for header in real_headers:
            if not (header.startswith("a/") or header.startswith("b/")):
                raise InstanceFailure(
                    "patchFormat",
                    "/tool/arguments/patch",
                    "diff paths must use an a/ or b/ repository prefix",
                )
            path = header[2:]
            validate_instance(
                path,
                relative_path_schema,
                relative_schema_path,
                "/tool/arguments/patch",
            )
            targets.append(path)
    return list(dict.fromkeys(targets))


def validate_tool_scope(fixture: Any) -> None:
    if not isinstance(fixture, dict) or fixture.get("messageType") != "TOOL_REQUEST":
        return
    requested_paths = fixture.get("requestedPaths")
    tool = fixture.get("tool")
    if not isinstance(requested_paths, list) or not isinstance(tool, dict):
        return
    arguments = tool.get("arguments")
    if not isinstance(arguments, dict):
        return
    if tool.get("name") == "read_file":
        path = arguments.get("path")
        if not isinstance(path, str) or not path_within_scope(path, requested_paths):
            raise InstanceFailure(
                "pathScope",
                "/tool/arguments/path",
                "read_file.path must be within requestedPaths",
            )
    if tool.get("name") == "search_code" and isinstance(arguments.get("roots"), list):
        for index, root in enumerate(arguments["roots"]):
            if not isinstance(root, str) or not path_within_scope(root, requested_paths):
                raise InstanceFailure(
                    "pathScope",
                    f"/tool/arguments/roots/{index}",
                    "search_code root must be within requestedPaths",
                )
    if tool.get("name") == "apply_patch" and isinstance(arguments.get("patch"), str):
        for target in unified_diff_targets(arguments["patch"]):
            if not path_within_scope(target, requested_paths):
                raise InstanceFailure(
                    "pathScope",
                    "/tool/arguments/patch",
                    f"patch target {target!r} is outside requestedPaths",
                )


def validate_fixture_correlations(fixture: Any) -> None:
    if not isinstance(fixture, dict):
        return
    status_url = fixture.get("statusUrl")
    if isinstance(status_url, str):
        if status_url.startswith("/api/agent-jobs/") and status_url.rsplit("/", 1)[-1] != fixture.get("jobId"):
            raise InstanceFailure(
                "correlation",
                "/statusUrl",
                "public statusUrl identifier must equal jobId",
            )
        if (
            status_url.startswith("/internal/coding/tool-executions/")
            and status_url.rsplit("/", 1)[-1] != fixture.get("executionId")
        ):
            raise InstanceFailure(
                "correlation",
                "/statusUrl",
                "internal statusUrl identifier must equal executionId",
            )
    result = fixture.get("result")
    if (
        isinstance(fixture.get("executionId"), str)
        and isinstance(result, dict)
        and isinstance(result.get("resultRef"), str)
        and result["resultRef"].startswith("/internal/coding/tool-executions/")
        and result["resultRef"].endswith("/result")
    ):
        result_execution_id = result["resultRef"].removesuffix("/result").rsplit("/", 1)[-1]
        if result_execution_id != fixture.get("executionId"):
            raise InstanceFailure(
                "correlation",
                "/result/resultRef",
                "resultRef identifier must equal executionId",
            )
    error = fixture.get("error")
    if (
        fixture.get("status") == "TIMED_OUT"
        and isinstance(error, dict)
        and error.get("executionId") != fixture.get("executionId")
    ):
        raise InstanceFailure(
            "correlation",
            "/error/executionId",
            "timeout error executionId must equal the result executionId",
        )
    if fixture.get("eventType") == "TOOL_EXECUTION_COMPLETED" and isinstance(
        fixture.get("payload"), dict
    ):
        validate_fixture_correlations(fixture["payload"])
    if (
        isinstance(fixture.get("executionId"), str)
        and isinstance(fixture.get("mediaType"), str)
        and isinstance(fixture.get("sizeBytes"), int)
        and isinstance(fixture.get("digest"), str)
        and isinstance(fixture.get("content"), str)
    ):
        validate_result_content_integrity(fixture, fixture["content"], "")
    messages = fixture.get("messages")
    if isinstance(messages, list):
        for index, message in enumerate(messages):
            if not isinstance(message, dict) or message.get("role") != "tool":
                continue
            result = message.get("result")
            if not isinstance(result, dict) or not isinstance(message.get("content"), str):
                continue
            prefix = f"/messages/{index}"
            result_ref = result.get("resultRef")
            execution_id = message.get("executionId")
            if isinstance(result_ref, str) and isinstance(execution_id, str):
                ref_execution_id = result_ref.removesuffix("/result").rsplit("/", 1)[-1]
                if ref_execution_id != execution_id:
                    raise InstanceFailure(
                        "correlation",
                        f"{prefix}/result/resultRef",
                        "tool resultRef identifier must equal message executionId",
                    )
            validate_result_content_integrity(result, message["content"], prefix)


def validate_result_content_integrity(
    reference: dict[str, Any], content: str, instance_prefix: str
) -> None:
    try:
        encoded = content.encode("utf-8", errors="strict")
    except UnicodeEncodeError as exc:
        raise InstanceFailure(
            "contentIntegrity", f"{instance_prefix}/content", "content must be valid UTF-8"
        ) from exc
    if reference.get("sizeBytes") != len(encoded):
        raise InstanceFailure(
            "contentIntegrity",
            f"{instance_prefix}/content",
            "UTF-8 byte length does not equal sizeBytes",
        )
    actual_digest = "sha256:" + hashlib.sha256(encoded).hexdigest()
    if reference.get("digest") != actual_digest:
        raise InstanceFailure(
            "contentIntegrity",
            f"{instance_prefix}/content",
            "SHA-256 digest does not match content",
        )
    if reference.get("mediaType") == "application/json":
        try:
            parsed = json.loads(
                content,
                object_pairs_hook=strict_object,
                parse_constant=reject_nonfinite_constant,
                parse_float=Decimal,
            )
        except (json.JSONDecodeError, ContractFailure) as exc:
            raise InstanceFailure(
                "contentIntegrity",
                f"{instance_prefix}/content",
                "application/json result must be strict JSON",
            ) from exc
        if jcs_serialize(parsed) != content:
            raise InstanceFailure(
                "contentIntegrity",
                f"{instance_prefix}/content",
                "application/json result must use the Stage 0 canonical JSON profile",
            )


def validate_idempotency_pair(fixture: Any, schema: Any, contract_path: Path) -> None:
    if not isinstance(fixture, dict) or set(fixture) != {"first", "second"}:
        raise ContractFailure("idempotency pair must contain exactly first and second")
    first = fixture["first"]
    second = fixture["second"]
    validate_instance(first, schema, contract_path, "/first")
    validate_instance(second, schema, contract_path, "/second")
    for prefix, item in (("/first", first), ("/second", second)):
        validate_tool_scope(item)
        validate_fixture_correlations(item)
        validate_model_schema_digests(item, prefix)
        validate_model_message_flow(item, prefix)
        validate_model_response_tool_ids(item, prefix)
    if first.get("idempotencyKey") != second.get("idempotencyKey"):
        raise InstanceFailure(
            "idempotencyPair", "", "pair must reuse the same idempotency key"
        )
    first_payload = canonical_idempotency_payload(first)
    second_payload = canonical_idempotency_payload(second)
    if not json_semantic_equal(first_payload, second_payload):
        raise InstanceFailure(
            "idempotencyConflict",
            "",
            "same key is reused with a different canonical request payload",
        )


def load_referenced_fixture(name: Any) -> tuple[Any, str]:
    if not isinstance(name, str) or not name.endswith(".json"):
        raise ContractFailure("referenced fixture must be a relative .json path")
    path = (FIXTURE_ROOT / name).resolve()
    try:
        path.relative_to(FIXTURE_ROOT.resolve())
    except ValueError as exc:
        raise ContractFailure(f"referenced fixture escapes fixture root: {name}") from exc
    return STORE.load(path), name


def validate_model_turn_pair(
    fixture: Any,
    request_schema: Any,
    response_schema: Any,
    contract_path: Path,
) -> set[str]:
    if not isinstance(fixture, dict) or set(fixture) != {
        "requestFixture",
        "responseFixture",
    }:
        raise ContractFailure(
            "model turn pair must contain exactly requestFixture and responseFixture"
        )
    request, request_name = load_referenced_fixture(fixture["requestFixture"])
    response, response_name = load_referenced_fixture(fixture["responseFixture"])
    reject_secret_shaped_keys(request, "/request")
    reject_secret_shaped_keys(response, "/response")
    validate_instance(request, request_schema, contract_path, "/request")
    validate_instance(response, response_schema, contract_path, "/response")
    validate_model_schema_digests(request, "/request")
    validate_model_message_flow(request, "/request")
    validate_model_response_tool_ids(response, "/response")

    for field in ("turnId", "jobId", "traceId", "idempotencyKey"):
        if request.get(field) != response.get(field):
            raise InstanceFailure(
                "correlation",
                f"/response/{field}",
                f"response {field} must equal request {field}",
            )

    request_format = request.get("responseFormat", {})
    response_format = response.get("responseFormat", {})
    if request_format.get("type") != response_format.get("type"):
        raise InstanceFailure(
            "correlation",
            "/response/responseFormat/type",
            "response format type must equal the requested type",
        )
    if request_format.get("type") == "JSON_SCHEMA":
        if request_format.get("schemaDigest") != response_format.get("schemaDigest"):
            raise InstanceFailure(
                "schemaDigest",
                "/response/responseFormat/schemaDigest",
                "response must echo the requested output schema digest",
            )
        validate_instance(
            response_format.get("structuredOutput"),
            request_format.get("outputSchema"),
            contract_path,
            "/response/responseFormat/structuredOutput",
        )

    registered_tools = {
        tool_schema["name"]: tool_schema
        for tool_schema in request.get("toolSchemas", [])
        if isinstance(tool_schema, dict) and isinstance(tool_schema.get("name"), str)
    }
    for index, tool_call in enumerate(response.get("toolCalls", [])):
        name = tool_call.get("name") if isinstance(tool_call, dict) else None
        if name not in registered_tools:
            raise InstanceFailure(
                "toolSchema",
                f"/response/toolCalls/{index}/name",
                "model response referenced an unregistered tool",
            )
        validate_instance(
            tool_call.get("arguments"),
            registered_tools[name].get("inputSchema"),
            contract_path,
            f"/response/toolCalls/{index}/arguments",
        )
    return {request_name, response_name}


def validate_tool_result_flow(fixture: Any) -> set[str]:
    if not isinstance(fixture, dict) or set(fixture) != {
        "terminalFixture",
        "contentFixture",
        "modelRequestFixture",
    }:
        raise ContractFailure(
            "tool result flow must contain exactly terminalFixture, contentFixture, and modelRequestFixture"
        )
    terminal, terminal_name = load_referenced_fixture(fixture["terminalFixture"])
    content, content_name = load_referenced_fixture(fixture["contentFixture"])
    model_request, model_request_name = load_referenced_fixture(
        fixture["modelRequestFixture"]
    )
    tool_path = CONTRACT_ROOT / "coding-agent" / "tool-request.schema.json"
    model_path = CONTRACT_ROOT / "coding-agent" / "model-turn.openapi.yaml"
    tool_document = STORE.load(tool_path)
    model_document = STORE.load(model_path)
    validate_instance(terminal, tool_document["$defs"]["ToolSucceeded"], tool_path, "/terminal")
    validate_instance(content, tool_document["$defs"]["ToolResultContent"], tool_path, "/content")
    validate_instance(
        model_request,
        model_document["components"]["schemas"]["ModelTurnRequest"],
        model_path,
        "/modelRequest",
    )
    validate_result_content_integrity(content, content["content"], "/content")
    validate_model_schema_digests(model_request, "/modelRequest")
    validate_model_message_flow(model_request, "/modelRequest")
    validate_fixture_correlations(model_request)

    for field in ("requestId", "toolCallId", "jobId", "traceId", "idempotencyKey", "executionId"):
        if terminal.get(field) != content.get(field):
            raise InstanceFailure(
                "resultFlowCorrelation",
                f"/content/{field}",
                f"result content {field} must equal the terminal result",
            )
    terminal_reference = terminal.get("result")
    if not isinstance(terminal_reference, dict):
        raise ContractFailure("ToolSucceeded fixture lacks ResultReference")
    for field in ("mediaType", "sizeBytes", "digest"):
        if terminal_reference.get(field) != content.get(field):
            raise InstanceFailure(
                "resultFlowCorrelation",
                f"/content/{field}",
                f"result content {field} must equal ResultReference",
            )
    if model_request.get("jobId") != content.get("jobId"):
        raise InstanceFailure(
            "resultFlowCorrelation", "/modelRequest/jobId", "model request must preserve jobId"
        )
    if model_request.get("traceId") != content.get("traceId"):
        raise InstanceFailure(
            "resultFlowCorrelation", "/modelRequest/traceId", "model request must preserve traceId"
        )
    matching_messages = [
        message
        for message in model_request.get("messages", [])
        if isinstance(message, dict)
        and message.get("role") == "tool"
        and message.get("toolCallId") == content.get("toolCallId")
    ]
    if len(matching_messages) != 1:
        raise InstanceFailure(
            "resultFlowCorrelation",
            "/modelRequest/messages",
            "exactly one model tool message must consume the fetched result",
        )
    message = matching_messages[0]
    if (
        message.get("executionId") != content.get("executionId")
        or not json_semantic_equal(message.get("result"), terminal_reference)
        or message.get("content") != content.get("content")
    ):
        raise InstanceFailure(
            "resultFlowCorrelation",
            "/modelRequest/messages",
            "model tool message must preserve executionId, ResultReference, and content",
        )
    return {terminal_name, content_name, model_request_name}


def schema_ref_coverage_target(
    schema_node: Any, document_path: Path, label: str
) -> tuple[str, str]:
    if not isinstance(schema_node, dict) or set(schema_node) != {"$ref"}:
        raise ContractFailure(f"{label}: operation body schema must use one direct $ref")
    ref = schema_node["$ref"]
    file_part, separator, fragment = ref.partition("#")
    if not separator:
        raise ContractFailure(f"{label}: schema $ref requires an explicit fragment")
    target_path = (document_path.parent / file_part).resolve() if file_part else document_path
    try:
        contract_name = target_path.relative_to(CONTRACT_ROOT).as_posix()
    except ValueError as exc:
        raise ContractFailure(f"{label}: schema coverage target escapes contract root") from exc
    return contract_name, "#" + fragment


def required_operation_fixture_targets() -> set[tuple[str, str]]:
    targets: set[tuple[str, str]] = set()
    for contract_name in (
        "public/openapi.yaml",
        "coding-agent/model-turn.openapi.yaml",
        "coding-agent/job-lifecycle.openapi.yaml",
        "orchestration/profile-version.openapi.yaml",
    ):
        document_path = CONTRACT_ROOT / contract_name
        document = STORE.load(document_path)
        for route, path_item in document["paths"].items():
            for method in ("get", "post", "put", "patch", "delete", "head", "options", "trace"):
                operation = path_item.get(method)
                if not isinstance(operation, dict):
                    continue
                operation_id = operation["operationId"]
                request_body = operation.get("requestBody")
                if request_body is not None:
                    request_body, request_path = resolve_object(request_body, document_path)
                    request_schema = (
                        request_body.get("content", {})
                        .get("application/json", {})
                        .get("schema")
                    )
                    if request_schema is not None:
                        targets.add(
                            schema_ref_coverage_target(
                                request_schema, request_path, f"{operation_id} request"
                            )
                        )
                for status, response_node in operation["responses"].items():
                    is_success_body = status.startswith("2") or (
                        operation_id == "getReadiness" and status == "503"
                    )
                    if not is_success_body:
                        continue
                    response, response_path = resolve_object(response_node, document_path)
                    response_schema = (
                        response.get("content", {})
                        .get("application/json", {})
                        .get("schema")
                    )
                    if response_schema is not None:
                        targets.add(
                            schema_ref_coverage_target(
                                response_schema,
                                response_path,
                                f"{operation_id} {status} response",
                            )
                        )
    return targets


def validate_oneof_ref_unions(
    cases: list[dict[str, Any]], valid_pointers: set[tuple[str, str]]
) -> None:
    for (contract_name, union_pointer), expected_refs in ONE_OF_REF_UNIONS.items():
        contract_path = CONTRACT_ROOT / contract_name
        document = STORE.load(contract_path)
        union_schema = STORE.pointer(document, union_pointer[1:])
        branches = union_schema.get("oneOf") if isinstance(union_schema, dict) else None
        if not isinstance(branches, list) or not branches:
            raise ContractFailure(f"{contract_name}{union_pointer}: expected a non-empty oneOf")
        actual_refs: list[str] = []
        for branch in branches:
            if not isinstance(branch, dict) or set(branch) != {"$ref"}:
                raise ContractFailure(
                    f"{contract_name}{union_pointer}: every Stage 0 branch must be one direct $ref"
                )
            actual_refs.append(branch["$ref"])
        if tuple(actual_refs) != expected_refs:
            raise ContractFailure(
                f"{contract_name}{union_pointer}: oneOf branch drift "
                f"expected={list(expected_refs)} actual={actual_refs}"
            )
        for branch_ref in expected_refs:
            branch_key = (contract_name, branch_ref)
            if branch_key not in valid_pointers:
                raise ContractFailure(f"missing direct valid oneOf branch coverage: {branch_key}")
            branch_case = next(
                case
                for case in cases
                if case.get("expected") == "VALID"
                and case.get("fixtureKind", "INSTANCE") == "INSTANCE"
                and (case["contract"], case.get("schemaPointer", "#")) == branch_key
            )
            branch_fixture = STORE.load(FIXTURE_ROOT / branch_case["fixture"])
            validate_instance(branch_fixture, union_schema, contract_path)

    contract_names = (
        "public/openapi.yaml",
        "coding-agent/model-turn.openapi.yaml",
        "coding-agent/job-lifecycle.openapi.yaml",
        "coding-agent/job-event.schema.json",
        "coding-agent/tool-request.schema.json",
        "coding-agent/error-code.schema.json",
    )
    for contract_name in contract_names:
        contract_path = CONTRACT_ROOT / contract_name
        document = STORE.load(contract_path)
        for pointer, node in iter_nodes_with_pointer(document):
            if not isinstance(node, dict) or "oneOf" not in node:
                continue
            union_pointer = "#" + pointer
            branches = node["oneOf"]
            if not isinstance(branches, list) or not branches:
                raise ContractFailure(f"{contract_name}{union_pointer}: oneOf must be non-empty")
            for index, branch in enumerate(branches):
                if not isinstance(branch, dict) or set(branch) != {"$ref"}:
                    raise ContractFailure(
                        f"{contract_name}{union_pointer}/oneOf/{index}: "
                        "Stage 0 unions require direct-ref branches for derived fixture coverage"
                    )
                branch_key = schema_ref_coverage_target(
                    branch,
                    contract_path,
                    f"{contract_name}{union_pointer}/oneOf/{index}",
                )
                if branch_key not in valid_pointers:
                    raise ContractFailure(
                        f"missing auto-discovered valid oneOf branch coverage: "
                        f"union={(contract_name, union_pointer)} branch={branch_key}"
                    )
                branch_case = next(
                    case
                    for case in cases
                    if case.get("expected") == "VALID"
                    and case.get("fixtureKind", "INSTANCE") == "INSTANCE"
                    and (case["contract"], case.get("schemaPointer", "#")) == branch_key
                )
                branch_fixture = STORE.load(FIXTURE_ROOT / branch_case["fixture"])
                validate_instance(branch_fixture, node, contract_path)


def validate_fixtures() -> tuple[int, int]:
    manifest_path = FIXTURE_ROOT / "manifest.json"
    manifest = STORE.load(manifest_path)
    reject_secret_shaped_keys(manifest)
    if manifest.get("schemaVersion") != PAYLOAD_VERSION:
        raise ContractFailure("fixture manifest has an unsupported schemaVersion")
    cases = manifest.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ContractFailure("fixture manifest cases must be a non-empty array")

    valid_count = 0
    invalid_count = 0
    seen_ids: set[str] = set()
    seen_fixtures: set[str] = set()
    referenced_fixtures: set[str] = set()
    allowed_contracts = {
        "public/openapi.yaml",
        "coding-agent/model-turn.openapi.yaml",
        "coding-agent/job-lifecycle.openapi.yaml",
        "coding-agent/job-event.schema.json",
        "coding-agent/tool-request.schema.json",
        "coding-agent/error-code.schema.json",
        "orchestration/profile-version.openapi.yaml",
    }
    allowed_case_fields = {
        "id",
        "contract",
        "schemaPointer",
        "fixture",
        "expected",
        "validationLayer",
        "expectedKeyword",
        "expectedInstancePath",
        "expectedErrorCode",
        "fixtureKind",
        "responseSchemaPointer",
    }
    allowed_layers = {"SYNTAX", "SCHEMA", "SEMANTIC", "POLICY"}
    for case in cases:
        if not isinstance(case, dict):
            raise ContractFailure("each fixture manifest case must be an object")
        unknown_fields = set(case) - allowed_case_fields
        if unknown_fields:
            raise ContractFailure(f"fixture case has unknown fields: {sorted(unknown_fields)}")
        case_id = case.get("id")
        if not case_id or case_id in seen_ids:
            raise ContractFailure(f"missing/duplicate fixture case id: {case_id!r}")
        seen_ids.add(case_id)
        fixture_name = case.get("fixture")
        if not isinstance(fixture_name, str) or fixture_name in seen_fixtures:
            raise ContractFailure(f"{case_id}: missing/duplicate fixture path {fixture_name!r}")
        seen_fixtures.add(fixture_name)
        contract_name = case.get("contract")
        if contract_name not in allowed_contracts:
            raise ContractFailure(f"{case_id}: unapproved contract {contract_name!r}")
        validation_layer = case.get("validationLayer")
        if validation_layer not in allowed_layers:
            raise ContractFailure(f"{case_id}: unknown validationLayer {validation_layer!r}")
        contract_path = CONTRACT_ROOT / contract_name
        contract = STORE.load(contract_path)
        pointer = case.get("schemaPointer", "#")
        if not pointer.startswith("#"):
            raise ContractFailure(f"{case_id}: schemaPointer must begin with #")
        schema = STORE.pointer(contract, pointer[1:])
        fixture_path = FIXTURE_ROOT / fixture_name
        fixture = STORE.load(fixture_path)

        failure: InstanceFailure | None = None
        try:
            reject_secret_shaped_keys(fixture)
            fixture_kind = case.get("fixtureKind", "INSTANCE")
            if fixture_kind == "INSTANCE":
                validate_instance(fixture, schema, contract_path)
                validate_tool_scope(fixture)
                validate_fixture_correlations(fixture)
                validate_model_schema_digests(fixture)
                validate_model_message_flow(fixture)
                validate_model_response_tool_ids(fixture)
            elif fixture_kind == "IDEMPOTENCY_PAIR":
                validate_idempotency_pair(fixture, schema, contract_path)
            elif fixture_kind == "MODEL_TURN_PAIR":
                response_pointer = case.get("responseSchemaPointer")
                if not isinstance(response_pointer, str) or not response_pointer.startswith("#"):
                    raise ContractFailure(
                        f"{case_id}: MODEL_TURN_PAIR requires responseSchemaPointer"
                    )
                response_schema = STORE.pointer(contract, response_pointer[1:])
                referenced_fixtures.update(
                    validate_model_turn_pair(
                        fixture,
                        schema,
                        response_schema,
                        contract_path,
                    )
                )
            elif fixture_kind == "TOOL_RESULT_FLOW":
                referenced_fixtures.update(validate_tool_result_flow(fixture))
            else:
                raise ContractFailure(f"{case_id}: unknown fixtureKind {fixture_kind!r}")
        except InstanceFailure as exc:
            failure = exc

        expected = case.get("expected")
        if expected == "VALID":
            valid_count += 1
            if failure is not None:
                raise ContractFailure(f"{case_id}: expected VALID, got {failure}")
        elif expected == "INVALID":
            invalid_count += 1
            if failure is None:
                raise ContractFailure(f"{case_id}: expected INVALID, but validation passed")
            expected_keyword = case.get("expectedKeyword")
            if expected_keyword and failure.keyword != expected_keyword:
                raise ContractFailure(
                    f"{case_id}: expected keyword {expected_keyword}, got {failure.keyword}: {failure}"
                )
            expected_path = case.get("expectedInstancePath")
            if expected_path is not None and failure.path != expected_path:
                raise ContractFailure(
                    f"{case_id}: expected path {expected_path}, got {failure.path}: {failure}"
                )
        else:
            raise ContractFailure(f"{case_id}: expected must be VALID or INVALID")

        expected_code = case.get("expectedErrorCode")
        if expected_code and find_error_code(fixture) != expected_code:
            raise ContractFailure(f"{case_id}: expected business error {expected_code}")

    actual_fixtures = {
        path.relative_to(FIXTURE_ROOT).as_posix()
        for path in FIXTURE_ROOT.rglob("*.json")
        if path.name != "manifest.json"
    }
    if not referenced_fixtures.issubset(seen_fixtures):
        raise ContractFailure(
            "composite fixture references files without their own manifest case: "
            f"{sorted(referenced_fixtures - seen_fixtures)}"
        )
    if actual_fixtures != seen_fixtures:
        missing = sorted(actual_fixtures - seen_fixtures)
        unknown = sorted(seen_fixtures - actual_fixtures)
        raise ContractFailure(
            f"fixture manifest coverage mismatch: orphan={missing}, missing={unknown}"
        )
    required_valid_pointers = {
        ("coding-agent/job-event.schema.json", "#/$defs/CodingJobRequested"),
        ("coding-agent/job-event.schema.json", "#/$defs/ToolExecutionCompleted"),
        ("coding-agent/job-event.schema.json", "#/$defs/ApprovalRecorded"),
        ("coding-agent/tool-request.schema.json", "#/$defs/ToolRequest"),
        ("coding-agent/tool-request.schema.json", "#/$defs/ToolAccepted"),
        ("coding-agent/tool-request.schema.json", "#/$defs/ToolSucceeded"),
        ("coding-agent/tool-request.schema.json", "#/$defs/ToolUnsuccessful"),
        ("coding-agent/tool-request.schema.json", "#/$defs/ToolTimedOut"),
        ("coding-agent/tool-request.schema.json", "#/$defs/ReadFileTool"),
        ("coding-agent/tool-request.schema.json", "#/$defs/SearchCodeTool"),
        ("coding-agent/tool-request.schema.json", "#/$defs/ApplyPatchTool"),
        ("coding-agent/tool-request.schema.json", "#/$defs/RunCheckTool"),
        ("coding-agent/error-code.schema.json", "#/$defs/NonRetryableError"),
        ("coding-agent/error-code.schema.json", "#/$defs/RetryableError"),
        ("coding-agent/error-code.schema.json", "#/$defs/AmbiguousToolTimeoutError"),
    }
    valid_pointers = {
        (case["contract"], case.get("schemaPointer", "#"))
        for case in cases
        if case.get("expected") == "VALID" and case.get("fixtureKind", "INSTANCE") == "INSTANCE"
    }
    missing_branch_coverage = sorted(required_valid_pointers - valid_pointers)
    if missing_branch_coverage:
        raise ContractFailure(
            f"missing direct valid oneOf branch coverage: {missing_branch_coverage}"
        )
    validate_oneof_ref_unions(cases, valid_pointers)
    missing_operation_coverage = sorted(
        required_operation_fixture_targets() - valid_pointers
    )
    if missing_operation_coverage:
        raise ContractFailure(
            f"missing direct operation body fixture coverage: {missing_operation_coverage}"
        )
    return valid_count, invalid_count


def validate_error_status_maps(
    public_path: Path,
    model_path: Path,
    error_path: Path,
) -> None:
    public_status_codes = {
        "400": {"VALIDATION_FAILED", "SCHEMA_VERSION_UNSUPPORTED", "INVALID_TRACE_ID", "IDEMPOTENCY_KEY_REQUIRED"},
        "401": {"AUTHENTICATION_REQUIRED"},
        "403": {"FORBIDDEN"},
        "404": {"RESOURCE_NOT_FOUND"},
        "409": {"IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_REQUEST_IN_PROGRESS", "JOB_STATE_CONFLICT", "KNOWLEDGE_VERSION_NOT_ACTIVE"},
        "422": {"CONNECTOR_SPEC_INVALID", "CONNECTOR_RESPONSE_INVALID"},
        "429": {"RATE_LIMITED"},
        "500": {"INTERNAL_ERROR"},
        "502": {"UPSTREAM_SERVICE_ERROR"},
        "503": {"SERVICE_NOT_READY", "PROVIDER_UNAVAILABLE", "LLM_NOT_CONFIGURED"},
        "504": {"UPSTREAM_TIMEOUT"},
    }
    internal_status_codes = {
        "400": {"CONTRACT_VALIDATION_FAILED", "UNSUPPORTED_SCHEMA_VERSION", "UNKNOWN_FIELD"},
        "401": {"SERVICE_AUTHENTICATION_FAILED"},
        "403": {"SERVICE_AUTHORIZATION_DENIED", "TOOL_NOT_ALLOWED", "PATH_POLICY_DENIED", "REPOSITORY_SCOPE_DENIED", "TOOL_APPROVAL_REQUIRED", "TOOL_APPROVAL_DENIED", "TOOL_APPROVAL_EXPIRED"},
        "404": {"JOB_NOT_FOUND", "JOB_EXPIRED", "TOOL_EXECUTION_NOT_FOUND"},
        "409": {"JOB_STATE_VERSION_CONFLICT", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS", "CANDIDATE_SHA_MISMATCH", "CONTEXT_DIGEST_MISMATCH", "TOOL_RESULT_NOT_READY"},
        "422": {"MODEL_RESPONSE_INVALID", "TOOL_ARGUMENTS_INVALID"},
        "429": {"MODEL_RATE_LIMITED"},
        "503": {"MODEL_NOT_CONFIGURED", "MODEL_CAPABILITY_UNSUPPORTED", "MODEL_PROVIDER_UNAVAILABLE", "TOOL_EXECUTOR_UNAVAILABLE", "INTERNAL_TRANSIENT_ERROR", "CODING_AGENT_NOT_AVAILABLE"},
        "504": {"MODEL_TIMEOUT"},
    }

    public = STORE.load(public_path)
    public_schemas = public["components"]["schemas"]
    public_codes = set(public_schemas["NonRetryablePublicError"]["properties"]["code"]["enum"])
    public_codes.update(public_schemas["RetryablePublicError"]["properties"]["code"]["enum"])
    error_document = STORE.load(error_path)
    definitions = error_document["$defs"]
    internal_codes = set(definitions["NonRetryableCode"]["enum"])
    internal_codes.update(definitions["RetryableCode"]["enum"])
    internal_codes.add("TOOL_EXECUTION_TIMEOUT")

    allowed_error_schema_refs = {
        public_path.resolve(): {"#/components/schemas/ErrorEnvelope"},
        model_path.resolve(): {
            "error-code.schema.json#/$defs/PreContextErrorEnvelope",
            "error-code.schema.json#/$defs/JobScopedErrorEnvelope",
            "error-code.schema.json#/$defs/ExecutionContextErrorEnvelope",
            "error-code.schema.json#/$defs/ErrorEnvelope",
        },
    }
    expected_response_component_envelopes = {
        public_path.resolve(): {
            "BadRequest": "#/components/schemas/ErrorEnvelope",
            "Unauthorized": "#/components/schemas/ErrorEnvelope",
            "Forbidden": "#/components/schemas/ErrorEnvelope",
            "NotFound": "#/components/schemas/ErrorEnvelope",
            "Conflict": "#/components/schemas/ErrorEnvelope",
            "UnprocessableEntity": "#/components/schemas/ErrorEnvelope",
            "TooManyRequests": "#/components/schemas/ErrorEnvelope",
            "InternalError": "#/components/schemas/ErrorEnvelope",
            "BadGateway": "#/components/schemas/ErrorEnvelope",
            "ServiceUnavailable": "#/components/schemas/ErrorEnvelope",
            "GatewayTimeout": "#/components/schemas/ErrorEnvelope",
        },
        model_path.resolve(): {
            "ContractError": "error-code.schema.json#/$defs/PreContextErrorEnvelope",
            "AuthenticationError": "error-code.schema.json#/$defs/PreContextErrorEnvelope",
            "AuthorizationError": "error-code.schema.json#/$defs/ErrorEnvelope",
            "JobNotFound": "error-code.schema.json#/$defs/JobScopedErrorEnvelope",
            "StateConflict": "error-code.schema.json#/$defs/JobScopedErrorEnvelope",
            "ModelResponseInvalid": "error-code.schema.json#/$defs/JobScopedErrorEnvelope",
            "RateLimited": "error-code.schema.json#/$defs/JobScopedErrorEnvelope",
            "ProviderUnavailable": "error-code.schema.json#/$defs/JobScopedErrorEnvelope",
            "DeadlineExceeded": "error-code.schema.json#/$defs/JobScopedErrorEnvelope",
            "ToolRequestInvalid": "error-code.schema.json#/$defs/JobScopedErrorEnvelope",
            "ToolExecutionNotFound": "error-code.schema.json#/$defs/ExecutionContextErrorEnvelope",
            "ToolExecutorUnavailable": "error-code.schema.json#/$defs/JobScopedErrorEnvelope",
            "ToolPolicyDenied": "error-code.schema.json#/$defs/JobScopedErrorEnvelope",
            "ToolStateConflict": "error-code.schema.json#/$defs/JobScopedErrorEnvelope",
            "ToolResultNotReady": "error-code.schema.json#/$defs/ExecutionContextErrorEnvelope",
        },
    }
    transport_codes_by_document: dict[Path, set[str]] = {
        public_path.resolve(): set(),
        model_path.resolve(): set(),
    }
    non_error_status_exceptions = {
        (public_path.resolve(), "GET", "/api/readiness", "503"),
    }

    for document_path, expected_codes, known_codes in (
        (public_path, public_status_codes, public_codes),
        (model_path, internal_status_codes, internal_codes),
    ):
        document = STORE.load(document_path)
        for route, path_item in document["paths"].items():
            for method in ("get", "post", "put", "patch", "delete", "head", "options", "trace"):
                operation = path_item.get(method)
                if not isinstance(operation, dict):
                    continue
                for status, response_node in operation["responses"].items():
                    response_component_name: str | None = None
                    if isinstance(response_node, dict) and set(response_node) == {"$ref"}:
                        response_ref = response_node["$ref"]
                        prefix = "#/components/responses/"
                        if isinstance(response_ref, str) and response_ref.startswith(prefix):
                            response_component_name = response_ref[len(prefix) :]
                    response, _ = resolve_object(response_node, document_path)
                    codes = response.get("x-error-codes")
                    schema_ref = (
                        response.get("content", {})
                        .get("application/json", {})
                        .get("schema", {})
                        .get("$ref", "")
                    )
                    is_error_envelope = schema_ref in allowed_error_schema_refs[document_path.resolve()]
                    numeric_error_status = bool(re.fullmatch(r"[45][0-9]{2}", status))
                    is_non_error_exception = (
                        document_path.resolve(), method.upper(), route, status
                    ) in non_error_status_exceptions
                    if numeric_error_status and not is_non_error_exception and not codes:
                        raise ContractFailure(
                            f"{document_path.name} {method.upper()} {route} {status}: "
                            "error response lacks x-error-codes"
                        )
                    if is_error_envelope and not codes:
                        raise ContractFailure(
                            f"{document_path.name} {method.upper()} {route} {status}: "
                            "error envelope lacks x-error-codes"
                        )
                    if not codes:
                        continue
                    if response_component_name is None:
                        raise ContractFailure(
                            f"{document_path.name} {method.upper()} {route} {status}: "
                            "error responses must directly reference a named boundary response component"
                        )
                    schema_node = (
                        response.get("content", {})
                        .get("application/json", {})
                        .get("schema")
                    )
                    if (
                        not isinstance(schema_node, dict)
                        or set(schema_node) != {"$ref"}
                        or not is_error_envelope
                    ):
                        raise ContractFailure(
                            f"{document_path.name} {method.upper()} {route} {status}: "
                            "x-error-codes requires the boundary's canonical direct error-envelope $ref"
                        )
                    expected_envelope_ref = expected_response_component_envelopes[
                        document_path.resolve()
                    ].get(response_component_name)
                    if expected_envelope_ref is None or schema_ref != expected_envelope_ref:
                        raise ContractFailure(
                            f"{document_path.name} {response_component_name}: "
                            f"expected envelope {expected_envelope_ref!r}, got {schema_ref!r}"
                        )
                    if not isinstance(codes, list) or not codes or len(codes) != len(set(codes)):
                        raise ContractFailure(
                            f"{document_path.name} {method.upper()} {route} {status}: invalid x-error-codes"
                        )
                    if status not in expected_codes or not set(codes).issubset(expected_codes[status]):
                        raise ContractFailure(
                            f"{document_path.name} {method.upper()} {route} {status}: code/status mismatch {codes}"
                        )
                    if not set(codes).issubset(known_codes):
                        raise ContractFailure(
                            f"{document_path.name} {method.upper()} {route} {status}: unknown error code"
                        )
                    transport_codes_by_document[document_path.resolve()].update(codes)

    public_transport_codes = transport_codes_by_document[public_path.resolve()]
    if public_transport_codes != public_codes:
        raise ContractFailure(
            "public transport error-code coverage mismatch: "
            f"missing={sorted(public_codes - public_transport_codes)} "
            f"extra={sorted(public_transport_codes - public_codes)}"
        )

    terminal_internal_codes = {"TOOL_EXECUTION_FAILED", "TOOL_EXECUTION_TIMEOUT"}
    tool_document = STORE.load(CONTRACT_ROOT / "coding-agent" / "tool-request.schema.json")
    failed_code = tool_document["$defs"]["ToolFailedError"]["properties"]["code"].get("const")
    denied_codes = set(
        tool_document["$defs"]["ToolDeniedError"]["properties"]["code"].get("enum", [])
    )
    expected_denied_codes = {
        "TOOL_ARGUMENTS_INVALID",
        "TOOL_NOT_ALLOWED",
        "PATH_POLICY_DENIED",
        "REPOSITORY_SCOPE_DENIED",
        "CANDIDATE_SHA_MISMATCH",
        "CONTEXT_DIGEST_MISMATCH",
        "TOOL_APPROVAL_REQUIRED",
        "TOOL_APPROVAL_DENIED",
        "TOOL_APPROVAL_EXPIRED",
    }
    timeout_ref = tool_document["$defs"]["ToolTimedOut"]["properties"]["error"].get("$ref")
    if (
        failed_code != "TOOL_EXECUTION_FAILED"
        or denied_codes != expected_denied_codes
        or timeout_ref != "error-code.schema.json#/$defs/AmbiguousToolTimeoutError"
    ):
        raise ContractFailure("terminal tool error-code partition drifted from the canonical map")
    job_event_document = STORE.load(
        CONTRACT_ROOT / "coding-agent" / "job-event.schema.json"
    )
    expected_completion_bindings = {
        "FailedToolCompletion": (
            "FAILED",
            "tool-request.schema.json#/$defs/ToolFailedError",
        ),
        "DeniedToolCompletion": (
            "DENIED",
            "tool-request.schema.json#/$defs/ToolDeniedError",
        ),
        "TimedOutToolCompletion": (
            "TIMED_OUT",
            "error-code.schema.json#/$defs/AmbiguousToolTimeoutError",
        ),
    }
    for definition_name, (expected_outcome, expected_error_ref) in (
        expected_completion_bindings.items()
    ):
        properties = job_event_document["$defs"][definition_name]["properties"]
        if (
            properties["outcome"].get("const") != expected_outcome
            or properties["error"].get("$ref") != expected_error_ref
        ):
            raise ContractFailure(
                f"{definition_name} outcome/error binding drifted from the canonical map"
            )
    internal_covered_codes = (
        transport_codes_by_document[model_path.resolve()] | terminal_internal_codes
    )
    if internal_covered_codes != internal_codes:
        raise ContractFailure(
            "internal transport/terminal error-code coverage mismatch: "
            f"missing={sorted(internal_codes - internal_covered_codes)} "
            f"extra={sorted(internal_covered_codes - internal_codes)}"
        )


def validate_security_boundaries(public_path: Path, model_path: Path) -> None:
    public = STORE.load(public_path)
    public_schemes = public.get("components", {}).get("securitySchemes", {})
    public_bearer = public_schemes.get("bearerAuth")
    if not isinstance(public_bearer, dict) or (
        public_bearer.get("type") != "http"
        or str(public_bearer.get("scheme", "")).lower() != "bearer"
    ):
        raise ContractFailure("public bearerAuth must be an HTTP Bearer scheme")
    # Login is unauthenticated by definition: it is the operation that issues the
    # Bearer session every other route requires. Health and readiness stay open so
    # the container probe reaches them without a session.
    unauthenticated_routes = {
        "/api/health", "/api/readiness", "/api/auth/login", "/api/auth/refresh"
    }
    for route, path_item in public["paths"].items():
        for method in ("get", "post", "put", "patch", "delete", "head", "options", "trace"):
            operation = path_item.get(method)
            if not isinstance(operation, dict):
                continue
            expected_security = [] if route in unauthenticated_routes else [{"bearerAuth": []}]
            if operation.get("security") != expected_security:
                raise ContractFailure(
                    f"public security boundary mismatch at {method.upper()} {route}"
                )

    model = STORE.load(model_path)
    model_schemes = model.get("components", {}).get("securitySchemes", {})
    internal_bearer = model_schemes.get("serviceCredential")
    if not isinstance(internal_bearer, dict) or (
        internal_bearer.get("type") != "http"
        or str(internal_bearer.get("scheme", "")).lower() != "bearer"
    ):
        raise ContractFailure("internal serviceCredential must be an HTTP Bearer scheme")
    for route, path_item in model["paths"].items():
        for method in ("get", "post", "put", "patch", "delete", "head", "options", "trace"):
            operation = path_item.get(method)
            if isinstance(operation, dict) and operation.get("security") != [
                {"serviceCredential": []}
            ]:
                raise ContractFailure(
                    f"internal security boundary mismatch at {method.upper()} {route}"
                )


def validate_semantics(public_path: Path, model_path: Path, error_path: Path) -> None:
    public = STORE.load(public_path)
    rag_schema = public["components"]["schemas"]["RagQueryRequest"]
    forbidden_rag_fields = {"projectId", "knowledgeVersionId"}
    if forbidden_rag_fields.intersection(rag_schema.get("properties", {})):
        raise ContractFailure("RAG request must not accept projectId or knowledgeVersionId")

    error_document = STORE.load(error_path)
    definitions = error_document["$defs"]
    non_retryable = set(definitions["NonRetryableCode"]["enum"])
    retryable = set(definitions["RetryableCode"]["enum"])
    if non_retryable.intersection(retryable):
        raise ContractFailure("retryable and non-retryable error code sets overlap")
    required_codes = {
        "UNSUPPORTED_SCHEMA_VERSION",
        "IDEMPOTENCY_KEY_REUSED",
        "MODEL_RATE_LIMITED",
        "MODEL_TIMEOUT",
        "TOOL_APPROVAL_DENIED",
        "PATH_POLICY_DENIED",
    }
    if not required_codes.issubset(non_retryable | retryable):
        missing = sorted(required_codes - (non_retryable | retryable))
        raise ContractFailure(f"canonical error mapping is missing: {missing}")
    validate_error_status_maps(public_path, model_path, error_path)
    validate_security_boundaries(public_path, model_path)


def validate_local_evaluator_regressions() -> int:
    probes = 0

    try:
        json.loads("NaN", parse_constant=reject_nonfinite_constant)
    except ContractFailure:
        probes += 1
    else:
        raise ContractFailure("local parser accepted non-standard NaN")

    huge_number = json.loads("1e9999", parse_float=Decimal)
    if not isinstance(huge_number, Decimal) or not huge_number.is_finite():
        raise ContractFailure("local parser coerced a finite JSON number to infinity")
    probes += 1

    pointer_document = {"": {"value": 1}, "/": 2, "array": [0, 1]}
    if STORE.pointer(pointer_document, "/") != {"value": 1}:
        raise ContractFailure("JSON pointer '/' was incorrectly treated as the document root")
    if STORE.pointer(pointer_document, "/~1") != 2:
        raise ContractFailure("JSON pointer tilde decoding failed")
    probes += 2
    for invalid_pointer in ("/~2", "/array/01", "/array/-1", "/array/+1", "/%FF"):
        try:
            STORE.pointer(pointer_document, invalid_pointer)
        except ContractFailure:
            probes += 1
        else:
            raise ContractFailure(f"local pointer evaluator accepted {invalid_pointer!r}")

    try:
        validate_instance(
            [1, 1.0],
            {"type": "array", "uniqueItems": True},
            CONTRACT_ROOT / "coding-agent" / "tool-request.schema.json",
        )
    except InstanceFailure as exc:
        if exc.keyword != "uniqueItems":
            raise ContractFailure(f"unexpected numeric equality failure: {exc}") from exc
        probes += 1
    else:
        raise ContractFailure("local uniqueItems evaluator treated 1 and 1.0 as distinct")

    for schema in ({"const": 1}, {"enum": [1]}):
        try:
            validate_instance(
                True,
                schema,
                CONTRACT_ROOT / "coding-agent" / "tool-request.schema.json",
            )
        except InstanceFailure:
            probes += 1
        else:
            raise ContractFailure("local evaluator treated JSON boolean true as number 1")
    validate_instance(
        Decimal("1.0"),
        {"type": "integer"},
        CONTRACT_ROOT / "coding-agent" / "tool-request.schema.json",
    )
    probes += 1

    for invalid_time in (
        "20260810T120000+09:00",
        "2026-08-10 12:00:00Z",
        "2026-08-10T12:00:00+09:00:30",
    ):
        try:
            validate_format(invalid_time, "date-time", "")
        except InstanceFailure:
            probes += 1
        else:
            raise ContractFailure(f"local date-time validator accepted {invalid_time!r}")
    validate_format("2026-08-10t12:00:00z", "date-time", "")
    probes += 1

    try:
        validate_format("https://exa mple.invalid/path", "uri", "")
    except InstanceFailure:
        probes += 1
    else:
        raise ContractFailure("local URI validator accepted embedded whitespace")
    for invalid_uri in (
        "https://example.invalid/한글",
        "https://example.invalid\\path",
        "https://example.invalid:not-a-port/path",
    ):
        try:
            validate_format(invalid_uri, "uri", "")
        except InstanceFailure:
            probes += 1
        else:
            raise ContractFailure(f"local URI validator accepted {invalid_uri!r}")
    validate_format("", "uri-reference", "")
    probes += 1

    try:
        validate_instance(
            {"unknown": 1},
            {"type": "object", "additionalProperties": "invalid"},
            CONTRACT_ROOT / "coding-agent" / "tool-request.schema.json",
        )
    except ContractFailure:
        probes += 1
    else:
        raise ContractFailure("local evaluator accepted invalid additionalProperties type")

    try:
        validate_instance(
            "contracts/README.md",
            {"$ref": "#/$defs/RelativePath", "const": "different/path"},
            CONTRACT_ROOT / "coding-agent" / "tool-request.schema.json",
        )
    except InstanceFailure as exc:
        if exc.keyword != "const":
            raise ContractFailure(f"unexpected $ref sibling failure: {exc}") from exc
        probes += 1
    else:
        raise ContractFailure("local evaluator ignored a $ref sibling keyword")

    reject_secret_shaped_keys({"secretRef": "cms-secret://reference-only"})
    probes += 1
    for secret_probe in (
        {"clientSecret": "placeholder"},
        {"value": "sk-abcdefghijklmnopqrstuvwxyz"},
        {"value": "AIza" + "A" * 35},
        {"value": "lsv2_" + "A" * 24},
        {"value": "Bearer " + "A" * 24},
    ):
        try:
            reject_secret_shaped_keys(secret_probe)
        except InstanceFailure as exc:
            if exc.keyword != "secretShape":
                raise ContractFailure(f"unexpected secret probe failure: {exc}") from exc
            probes += 1
        else:
            raise ContractFailure("local fixture scanner accepted secret-shaped data")

    for unsafe_jcs_number in (Decimal("0.5"), Decimal("9007199254740992"), Decimal("1E30")):
        try:
            jcs_serialize(unsafe_jcs_number)
        except ContractFailure:
            probes += 1
        else:
            raise ContractFailure(
                f"Stage 0 JCS profile accepted unsafe number {unsafe_jcs_number}"
            )

    for invalid_ref in (
        ".\\job-event.schema.json#/$defs/Uuid",
        "C:/contracts/job-event.schema.json#/$defs/Uuid",
        "../job-event.schema.json#/$defs/Uuid",
        "job-event.schema.json?query#/$defs/Uuid",
    ):
        try:
            STORE.resolve_ref(
                CONTRACT_ROOT / "coding-agent" / "tool-request.schema.json",
                invalid_ref,
            )
        except ContractFailure:
            probes += 1
        else:
            raise ContractFailure(f"local $ref resolver accepted non-portable path {invalid_ref!r}")

    try:
        validate_embedded_schema_profile(
            {"type": "object", "dependentRequired": {"a": ["b"]}}, ""
        )
    except InstanceFailure as exc:
        if exc.keyword != "schemaProfile":
            raise ContractFailure(f"unexpected embedded schema profile failure: {exc}") from exc
        probes += 1
    else:
        raise ContractFailure("embedded schema profile ignored an unsupported assertion keyword")

    return probes


def main() -> int:
    public_path = CONTRACT_ROOT / "public" / "openapi.yaml"
    model_path = CONTRACT_ROOT / "coding-agent" / "model-turn.openapi.yaml"
    job_lifecycle_path = CONTRACT_ROOT / "coding-agent" / "job-lifecycle.openapi.yaml"
    profile_version_path = CONTRACT_ROOT / "orchestration" / "profile-version.openapi.yaml"
    schema_paths = sorted((CONTRACT_ROOT / "coding-agent").glob("*.schema.json"))
    contract_paths = [
        public_path,
        model_path,
        job_lifecycle_path,
        profile_version_path,
        *schema_paths,
    ]

    try:
        regression_probe_count = validate_local_evaluator_regressions()
        for path in contract_paths:
            reject_secret_shaped_values(STORE.load(path))
        reject_secret_shaped_schema_properties(contract_paths)
        operation_count = validate_openapi_profile(public_path, "/api")
        operation_count += validate_openapi_profile(model_path, "/internal")
        operation_count += validate_openapi_profile(job_lifecycle_path, "/internal/dev")
        operation_count += validate_openapi_profile(
            profile_version_path,
            "/internal/ai",
            frozenset({("getActiveProfileVersionSnapshot", "200")}),
        )
        reference_count = validate_local_references(contract_paths)
        strict_object_count = validate_schema_profile(contract_paths)
        valid_count, invalid_count = validate_fixtures()
        validate_semantics(
            public_path,
            model_path,
            CONTRACT_ROOT / "coding-agent" / "error-code.schema.json",
        )
    except (ContractFailure, InstanceFailure, KeyError, TypeError) as exc:
        print(f"FAIL {exc}", file=sys.stderr)
        return 1

    print(f"PASS parsed contract documents: {len(contract_paths)}")
    print(f"PASS local evaluator regression probes: {regression_probe_count}")
    print(f"PASS OpenAPI operations: {operation_count}")
    print(f"PASS resolved local references: {reference_count}")
    print(f"PASS explicit object-schema policies: {strict_object_count}")
    print(f"PASS golden fixtures: {valid_count} valid, {invalid_count} invalid")
    print(
        "PASS semantic invariants: boundary, HTTP/error/retry mapping, "
        "security, RAG active-version isolation"
    )
    print(
        "WARN independent official OpenAPI/JSON Schema meta-validation is deferred "
        "until a locked Stage 1/CI tool is approved"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
