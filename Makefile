# Entry points for the whole monorepo. Each component also works with its own tooling.
# Needs: Node 24 + corepack (pnpm), a JDK 17+. `android-*` targets also need the Android SDK.

.DEFAULT_GOAL := help
.PHONY: help setup check ts-check core-check android-check format server-dev compose-config

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

android-check: ## Build and test the Android apps (needs the Android SDK)
	cd android && ./gradlew check assembleDebug

format: ## Auto-format TypeScript and Kotlin
	pnpm format
	cd core && ./gradlew spotlessApply
	cd android && ./gradlew spotlessApply

server-dev: ## Run the web service with auto-reload on :8080
	pnpm --filter @overdrive/server dev

compose-config: ## Validate compose.yaml
	POSTGRES_PASSWORD=x docker compose config --quiet
