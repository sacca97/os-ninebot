# OpenRide Android helpers. Run from android/.  `make help` lists targets.
# Pick a device with SERIAL=<adb serial> when more than one is attached.

JAVA_HOME ?= $(firstword $(wildcard /opt/homebrew/opt/openjdk@21 $(HOME)/.jdks/jbr-21.0.11))
export JAVA_HOME
export ANDROID_HOME ?= $(HOME)/Library/Android/sdk
# Let Gradle's installDebug follow SERIAL too (only when one is given).
ifneq ($(SERIAL),)
export ANDROID_SERIAL := $(SERIAL)
endif

GRADLE := ./gradlew
ADB    := $(ANDROID_HOME)/platform-tools/adb $(if $(SERIAL),-s $(SERIAL))
# Debug and release are separate apps on the phone: debug is com.sacca.openride.debug ("OpenRide Debug"), release is
# com.sacca.openride. start/stop/logs/uninstall act on the debug app, or on the release one with VARIANT=release
# (the *-release targets set it for you).
VARIANT ?= debug
VERSION ?=
export OPENRIDE_VERSION := $(VERSION)
PKG    := com.sacca.openride$(if $(filter release,$(VARIANT)),,.debug)
APK    := app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE := app/build/outputs/apk/release/app-release.apk

.PHONY: help build test lint check install run start stop logs uninstall devices clean \
        keystore build-release release install-release run-release uninstall-release

help:  ## list targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-18s %s\n", $$1, $$2}'

build:  ## assemble the debug APK
	$(GRADLE) :app:assembleDebug

test:  ## run :core unit tests
	$(GRADLE) :core:test

lint:  ## run Android lint
	$(GRADLE) :app:lintDebug

check: test build lint  ## tests + build + lint

install:  ## build and install on the device/emulator
	$(GRADLE) :app:installDebug

start:  ## launch the installed app
	$(ADB) shell monkey -p $(PKG) -c android.intent.category.LAUNCHER 1

run: install start  ## install, then launch

stop:  ## force-stop the app
	$(ADB) shell am force-stop $(PKG)

logs:  ## follow this app's logcat
	$(ADB) logcat --pid=$$($(ADB) shell pidof $(PKG))

uninstall:  ## remove the app (also wipes its stored data)
	$(ADB) uninstall $(PKG)

devices:  ## list adb devices
	$(ADB) devices

keystore:  ## create the local release signing key (android/release.jks + keystore.properties, both git-ignored)
	@test ! -e keystore.properties || { echo "keystore.properties already exists; not overwriting it."; exit 1; }
	@PW=$$(openssl rand -hex 16); \
	 $(JAVA_HOME)/bin/keytool -genkeypair -keystore release.jks -alias openride-release -keyalg RSA -keysize 4096 -validity 10000 \
	   -storepass $$PW -keypass $$PW -dname "CN=OpenRide, O=local" && \
	 printf 'storeFile=release.jks\nstorePassword=%s\nkeyAlias=openride-release\nkeyPassword=%s\n' $$PW $$PW > keystore.properties && \
	 chmod 600 keystore.properties release.jks && \
	 echo "Created release.jks and keystore.properties. BACK THEM UP: updates to an installed release app must be signed with this same key."

build-release:  ## build the signed, minified release APK locally (needs `make keystore` once)
	@test -f keystore.properties || { echo "No signing key yet: run 'make keystore' once."; exit 1; }
	$(GRADLE) :app:assembleRelease
	@echo "-> $(APK_RELEASE)"

release:  ## bump, commit, tag and push VERSION=0.0.1; GitHub builds and publishes
	@set -eu; \
	python3 -c 'import os, re, sys; sys.exit(0 if re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", os.environ["OPENRIDE_VERSION"]) else "Usage: make release VERSION=x.y.z (no leading zeros)")'; \
	test -z "$$(git status --porcelain)" || { echo "Working tree not clean."; exit 1; }; \
	if git rev-parse --verify --quiet "refs/tags/$$OPENRIDE_VERSION" >/dev/null; then echo "Tag already exists."; exit 1; fi; \
	if ! grep -Fq "versionName = \"$$OPENRIDE_VERSION\"" app/build.gradle.kts; then \
	  python3 -c 'import os, re; from pathlib import Path; p = Path("app/build.gradle.kts"); s = p.read_text(); code = int(re.search(r"versionCode = (\d+)", s)[1]) + 1; assert code <= 2100000000, "Android versionCode limit reached"; s = re.sub(r"versionCode = \d+", "versionCode = " + str(code), s, count=1); s = re.sub(r"versionName = \"[^\"\n]+\"", "versionName = " + chr(34) + os.environ["OPENRIDE_VERSION"] + chr(34), s, count=1); p.write_text(s)'; \
	  git add app/build.gradle.kts; \
	  git commit -m "Release $$OPENRIDE_VERSION"; \
	fi; \
	git tag "$$OPENRIDE_VERSION"; \
	git push --atomic origin HEAD "refs/tags/$$OPENRIDE_VERSION"

install-release: build-release  ## build and install the release app (next to the debug one)
	@output=$$($(ADB) install -r $(APK_RELEASE) 2>&1); status=$$?; \
	 printf '%s\n' "$$output"; \
	 if [ $$status -ne 0 ]; then \
	   case "$$output" in \
	     *"more than one device/emulator"*) echo "Select a device: make devices, then make install-release SERIAL=<adb-serial>." ;; \
	     *INSTALL_FAILED_UPDATE_INCOMPATIBLE*) echo "A copy signed with a different key is installed: make uninstall-release$(if $(SERIAL), SERIAL=$(SERIAL)) (wipes its data) and retry." ;; \
	   esac; \
	 fi; \
	 exit $$status

run-release: VARIANT = release
run-release: install-release start  ## install the release app, then launch it

uninstall-release: VARIANT = release
uninstall-release: uninstall  ## remove the release app (also wipes its stored data)

clean:  ## gradle clean
	$(GRADLE) clean
