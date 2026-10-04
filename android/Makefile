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
PKG    := com.sacca.openride$(if $(filter release,$(VARIANT)),,.debug)
APK    := app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE := app/build/outputs/apk/release/app-release.apk

.PHONY: help build test lint check install run start stop logs uninstall devices clean \
        keystore release install-release run-release uninstall-release

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
	 $(JAVA_HOME)/bin/keytool -genkeypair -keystore release.jks -alias openride -keyalg RSA -keysize 4096 -validity 10000 \
	   -storepass $$PW -keypass $$PW -dname "CN=OpenRide, O=local" && \
	 printf 'storeFile=release.jks\nstorePassword=%s\nkeyAlias=openride\nkeyPassword=%s\n' $$PW $$PW > keystore.properties && \
	 chmod 600 keystore.properties release.jks && \
	 echo "Created release.jks and keystore.properties. BACK THEM UP: updates to an installed release app must be signed with this same key."

release:  ## build the signed, minified release APK (needs `make keystore` once)
	@test -f keystore.properties || { echo "No signing key yet: run 'make keystore' once."; exit 1; }
	$(GRADLE) :app:assembleRelease
	@echo "-> $(APK_RELEASE)"

install-release: release  ## build and install the release app (next to the debug one)
	$(ADB) install -r $(APK_RELEASE) || { echo "If this says INSTALL_FAILED_UPDATE_INCOMPATIBLE, a copy signed with a different key is installed: make uninstall-release (wipes its data) and retry."; exit 1; }

run-release: VARIANT = release
run-release: install-release start  ## install the release app, then launch it

uninstall-release: VARIANT = release
uninstall-release: uninstall  ## remove the release app (also wipes its stored data)

clean:  ## gradle clean
	$(GRADLE) clean
