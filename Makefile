# Entry points for the whole monorepo. Each component also works with its own tooling.
# Needs: Node 24 + corepack (pnpm), a JDK 17+. `android-*` targets also need the Android SDK.

.DEFAULT_GOAL := help
.PHONY: help setup check ts-check core-check android-check macos-tokens macos-core macos-gen macos-test format server-dev compose-config

help: ## List targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-16s %s\n", $$1, $$2}'

setup: ## Install TypeScript dependencies
	pnpm install

check: ts-check core-check ## Everything that builds without the Android SDK

ts-check: ## Lint, typecheck and test the TypeScript workspaces
	pnpm check
	pnpm build

core-check: ## Build and test the shared Kotlin core
	cd core && ./gradlew check

android-check: ## Build and test the Android apps incl. unsigned release (needs the Android SDK)
	cd android && ./gradlew check assembleDebug assembleRelease

macos-tokens: ## Regenerate Swift tokens, icons, and fonts for macOS
	node design/tools/build-tokens.mjs
	node design/tools/build-icons.mjs

macos-core: ## Build OverdriveCore.xcframework for Apple Silicon MacBooks (needs macOS)
	cd core && ./gradlew assembleOverdriveCoreDebugXCFramework

macos-gen: ## Generate macos/Overdrive.xcodeproj with XcodeGen (needs macOS)
	cd macos && ([ -f Config/LocalSigning.xcconfig ] || cp Config/LocalSigning.xcconfig.example Config/LocalSigning.xcconfig) && xcodegen generate

macos-test: macos-core macos-gen ## Run Kotlin/Native core tests and Swift unit tests (needs macOS)
	cd core && ./gradlew macosArm64Test
	xcodebuild test -project macos/Overdrive.xcodeproj -scheme Overdrive -destination 'platform=macOS,arch=arm64' CODE_SIGNING_ALLOWED=NO

format: ## Auto-format TypeScript and Kotlin
	pnpm format
	cd core && ./gradlew spotlessApply
	cd android && ./gradlew spotlessApply

server-dev: ## Run the web service with auto-reload on :8080
	pnpm --filter @overdrive/server dev

compose-config: ## Validate compose.yaml
	POSTGRES_PASSWORD=x docker compose config --quiet
