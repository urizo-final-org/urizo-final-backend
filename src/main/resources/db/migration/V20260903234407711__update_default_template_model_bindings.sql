UPDATE app.ai_profile_default_template
SET snapshot_json = jsonb_set(
        snapshot_json,
        '{modelBindings}',
        $$
        {
          "analyze": {
            "primary": "google-genai-gemini-3-6-flash",
            "fallback": [],
            "selections": {
              "google-genai-gemini-3-6-flash": {
                "provider": "GOOGLE_GENAI",
                "model": "gemini-3.6-flash",
                "inference": {"reasoningIntensity": "MEDIUM"}
              }
            }
          },
          "code": {
            "primary": "google-genai-gemini-3-6-flash",
            "fallback": [],
            "selections": {
              "google-genai-gemini-3-6-flash": {
                "provider": "GOOGLE_GENAI",
                "model": "gemini-3.6-flash",
                "inference": {"reasoningIntensity": "MEDIUM"}
              }
            }
          },
          "review": {
            "primary": "google-genai-gemini-3-6-flash",
            "fallback": [],
            "selections": {
              "google-genai-gemini-3-6-flash": {
                "provider": "GOOGLE_GENAI",
                "model": "gemini-3.6-flash",
                "inference": {"reasoningIntensity": "MEDIUM"}
              }
            }
          }
        }
        $$::jsonb,
        false),
    updated_at = CURRENT_TIMESTAMP
WHERE profile_key = 'LLM_OPS'
  AND snapshot_json->'modelBindings' IS DISTINCT FROM $$
      {
        "analyze":{"primary":"google-genai-gemini-3-6-flash","fallback":[],"selections":{"google-genai-gemini-3-6-flash":{"provider":"GOOGLE_GENAI","model":"gemini-3.6-flash","inference":{"reasoningIntensity":"MEDIUM"}}}},
        "code":{"primary":"google-genai-gemini-3-6-flash","fallback":[],"selections":{"google-genai-gemini-3-6-flash":{"provider":"GOOGLE_GENAI","model":"gemini-3.6-flash","inference":{"reasoningIntensity":"MEDIUM"}}}},
        "review":{"primary":"google-genai-gemini-3-6-flash","fallback":[],"selections":{"google-genai-gemini-3-6-flash":{"provider":"GOOGLE_GENAI","model":"gemini-3.6-flash","inference":{"reasoningIntensity":"MEDIUM"}}}}
      }
      $$::jsonb;

UPDATE app.ai_profile_default_template
SET snapshot_json = jsonb_set(
        snapshot_json,
        '{modelBindings}',
        $$
        {
          "analyze": {
            "primary": "google-genai-gemini-3-6-flash",
            "fallback": [],
            "selections": {
              "google-genai-gemini-3-6-flash": {
                "provider": "GOOGLE_GENAI",
                "model": "gemini-3.6-flash",
                "inference": {"reasoningIntensity": "MEDIUM"}
              }
            }
          },
          "preview": {
            "primary": "google-genai-gemini-3-6-flash",
            "fallback": [],
            "selections": {
              "google-genai-gemini-3-6-flash": {
                "provider": "GOOGLE_GENAI",
                "model": "gemini-3.6-flash",
                "inference": {"reasoningIntensity": "MEDIUM"}
              }
            }
          }
        }
        $$::jsonb,
        false),
    updated_at = CURRENT_TIMESTAMP
WHERE profile_key = 'NATURAL_CMS'
  AND snapshot_json->'modelBindings' IS DISTINCT FROM $$
      {
        "analyze":{"primary":"google-genai-gemini-3-6-flash","fallback":[],"selections":{"google-genai-gemini-3-6-flash":{"provider":"GOOGLE_GENAI","model":"gemini-3.6-flash","inference":{"reasoningIntensity":"MEDIUM"}}}},
        "preview":{"primary":"google-genai-gemini-3-6-flash","fallback":[],"selections":{"google-genai-gemini-3-6-flash":{"provider":"GOOGLE_GENAI","model":"gemini-3.6-flash","inference":{"reasoningIntensity":"MEDIUM"}}}}
      }
      $$::jsonb;
