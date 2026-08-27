## Description

Please describe the change and the problem it solves. Include the user-visible impact.

## Related issues / design docs

Closes #(issue)

## Key implementation boundaries

- Module(s) touched:
- Any deliberate limitations:

## Verification

List the commands you ran and their results:

```powershell
.\gradlew.bat test
python -m pytest
```

## UI changes

If this changes the UI, please add screenshots or a screen recording, and confirm the layout against `docs/mobile-ui-layout-spec.md` on phone-sized screens.

## Checklist

- [ ] Read the [contributing guidelines](CONTRIBUTING.md)
- [ ] Tests added or updated where practical
- [ ] User-facing error codes registered in `UserFacingErrorCodes.kt` and `docs/error-codes.md` (if applicable)
- [ ] New runtime tools routed through `ToolPolicyPipeline` (if applicable)