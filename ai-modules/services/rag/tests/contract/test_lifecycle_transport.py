from fastapi.testclient import TestClient

from sage_vault_rag.transport.http.app import create_app
from tests.contract._fakes import dependencies_for_test, settings_for_test


class TrackingRegistration:
    def __init__(self) -> None:
        self.calls: list[str] = []

    async def register(self) -> None:
        self.calls.append("register")

    async def close(self) -> None:
        self.calls.append("close")


def test_lifespan_registers_then_closes() -> None:
    registration = TrackingRegistration()
    app = create_app(settings_for_test(), dependencies=dependencies_for_test(), registration=registration)

    with TestClient(app):
        assert registration.calls == ["register"]

    assert registration.calls == ["register", "close"]
