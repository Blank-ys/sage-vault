from typing import Any

from sage_vault_rag.bootstrap.settings import Settings


class NacosRegistration:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._service: Any = None

    async def register(self) -> None:
        from v2.nacos import (  # type: ignore[import-untyped]
            ClientConfigBuilder,
            NacosNamingService,
            RegisterInstanceParam,
        )

        config = ClientConfigBuilder().server_address(self._settings.nacos_server_address).build()
        self._service = await NacosNamingService.create_naming_service(config)
        await self._service.register_instance(
            RegisterInstanceParam(
                service_name=self._settings.service_name,
                ip=self._settings.service_ip,
                port=self._settings.service_port,
            )
        )

    async def close(self) -> None:
        if self._service is None:
            return
        from v2.nacos import DeregisterInstanceParam

        await self._service.deregister_instance(
            DeregisterInstanceParam(
                service_name=self._settings.service_name,
                ip=self._settings.service_ip,
                port=self._settings.service_port,
            )
        )
        await self._service.shutdown()
