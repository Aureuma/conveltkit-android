.PHONY: build check test

build:
	./scripts/build-guard.sh gradle build --max-workers=2 --no-daemon --no-parallel

check:
	./scripts/build-guard.sh gradle check --max-workers=2 --no-daemon --no-parallel

test:
	./scripts/build-guard.sh gradle test --max-workers=2 --no-daemon --no-parallel
