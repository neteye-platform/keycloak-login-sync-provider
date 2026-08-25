DOCKER_HOST = unix:///run/user/$(shell id -u)/podman/podman.sock
export DOCKER_HOST

COMPOSE := docker compose -f podman-compose.yml

.PHONY: build deploy up down reset logs test fmt

build:
	scripts/test.sh clean test-compile package

deploy: build
	$(COMPOSE) up -d --force-recreate keycloak

up:
	$(COMPOSE) up -d

down:
	$(COMPOSE) down

reset:
	$(COMPOSE) down -v --remove-orphans
	rm -rf target

logs:
	$(COMPOSE) logs -f

test:
	scripts/test.sh clean verify

fmt:
	scripts/test.sh spotless:apply
