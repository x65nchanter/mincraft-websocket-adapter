pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
		mavenCentral()
		gradlePluginPortal()
	}

	plugins {
		id("net.fabricmc.fabric-loom-remap") version providers.gradleProperty("loom_version")
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
	repositories {
		mavenCentral()
		maven {
			name = "Mojang"
			url = uri("https://libraries.minecraft.net/")
		}

		maven {
			name = "FabricPublic"
			url = uri("https://maven.fabricmc.net")
		}

		maven {
			name = "JitPack"
			url = uri("https://jitpack.io")
		}
	}
}

// Should match your modid
rootProject.name = "minecraft-websocket-bridge"

include("core")
