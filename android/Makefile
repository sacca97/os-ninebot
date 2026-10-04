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
PKG    := openride.app
APK    := app/build/outputs/apk/debug/app-debug.apk

.PHONY: help build test lint check install run start stop logs uninstall devices clean

help:  ## list targets
	@grep -E '^[a-z]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-10s %s\n", $$1, $$2}'

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

clean:  ## gradle clean
	$(GRADLE) clean
