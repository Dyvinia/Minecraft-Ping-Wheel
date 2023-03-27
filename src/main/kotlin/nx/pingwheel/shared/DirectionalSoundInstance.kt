package nx.pingwheel.shared

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.sound.MovingSoundInstance
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.Vec3d
import nx.pingwheel.client.util.Game
import kotlin.math.*

@Environment(EnvType.CLIENT)
class DirectionalSoundInstance(
	sound: SoundEvent,
	category: SoundCategory,
	volume: Float,
	pitch: Float,
	pos: Vec3d,
) : MovingSoundInstance(sound, category) {

	private val pos: Vec3d

	init {
		this.volume = volume
		this.pitch = pitch
		this.pos = pos

		updateSoundPos()
	}

	override fun tick() {
		updateSoundPos()
	}

	private fun updateSoundPos() {
		val playerPos = Game.player?.pos ?: return this.setDone()

		val vecBetween = playerPos.relativize(this.pos)
		val mappedDistance = min(vecBetween.length(), 64.0) / 64.0 * 8.0
		val soundDirection = vecBetween.normalize().multiply(mappedDistance)
		val soundPos = playerPos.add(soundDirection)

		this.x = soundPos.x
		this.y = soundPos.y
		this.z = soundPos.z
	}
}
