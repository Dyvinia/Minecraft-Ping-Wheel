package nx.pingwheel.client

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.PacketSender
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawableHelper
import net.minecraft.client.network.ClientPlayNetworkHandler
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.EntityType
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.mob.Angerable
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.mob.Monster
import net.minecraft.network.PacketByteBuf
import net.minecraft.sound.SoundCategory
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.ColorHelper
import net.minecraft.util.math.Matrix4f
import net.minecraft.util.math.Vec2f
import net.minecraft.util.math.Vec3d
import nx.pingwheel.PingWheel
import nx.pingwheel.client.util.*
import nx.pingwheel.shared.Constants
import nx.pingwheel.shared.DirectionalSoundInstance
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import xaero.pac.OpenPartiesAndClaims
import kotlin.math.*

object Core {

	private const val REACH_DISTANCE = 1024.0
	private const val TPS = 20

	private val config = PingWheelConfigHandler.getInstance().config
	private var pingRepo = mutableListOf<PingData>()
	private var queuePing = false

	@JvmStatic
	fun markLocation() {
		queuePing = true
	}

	private fun processPing(tickDelta: Float) {
		if (!queuePing) {
			return
		}

		queuePing = false
		val cameraEntity = Game.cameraEntity ?: return
		val cameraDirection = cameraEntity.getRotationVec(tickDelta)
		val hitResult = RayCasting.traceDirectional(cameraDirection, tickDelta, min(REACH_DISTANCE, config.pingDistance.toDouble()), cameraEntity.isSneaking)
		val username = (cameraEntity as ClientPlayerEntity).gameProfile.name
		val opacParty = OpenPartiesAndClaims.INSTANCE.clientDataInternal.clientPartyStorage.party?.id?.toString()

		if (hitResult == null || hitResult.type == HitResult.Type.MISS) {
			return
		}
		val packet = PacketByteBufs.create()
		packet.writeString(opacParty ?: config.channel)
		packet.writeDouble(hitResult.pos.x)
		packet.writeDouble(hitResult.pos.y)
		packet.writeDouble(hitResult.pos.z)

		packet.writeString(username)

		if (hitResult.type == HitResult.Type.ENTITY) {
			packet.writeBoolean(true)
			packet.writeUuid((hitResult as EntityHitResult).entity.uuid)
		} else {
			packet.writeBoolean(false)
		}

		ClientPlayNetworking.send(Constants.C2S_PING_LOCATION, packet)
	}

	private fun getDistanceScale(distance: Float): Float {
		val scaleMin = 1f
		val scale = 2f / distance.pow(0.3f)

		return max(scaleMin, scale)
	}

	@JvmStatic
	fun onReceivePing(
		client: MinecraftClient,
		handler: ClientPlayNetworkHandler,
		buf: PacketByteBuf,
		responseSender: PacketSender
	) {
		val channel = buf.readString()

		val opacParty = OpenPartiesAndClaims.INSTANCE.clientDataInternal.clientPartyStorage.party?.id?.toString()

		if (channel != (opacParty ?: config.channel)) {
			return
		}

		val pingPos = Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble())

		if (config.pingDistance < 2048) {
			val vecToPing = Game.player?.pos?.relativize(pingPos)

			if (vecToPing != null && vecToPing.length() > config.pingDistance.toDouble()) {
				return
			}
		}

		val username = buf.readString()

		val uuid = if (buf.readBoolean()) buf.readUuid() else null

		client.execute {
			pingRepo.add(PingData(pingPos, uuid, username, Game.world?.time?.toInt() ?: 0))

			Game.soundManager.play(
				DirectionalSoundInstance(
					PingWheel.PING_SOUND_EVENT,
					SoundCategory.MASTER,
					config.pingVolume / 100f,
					1f,
					pingPos,
				)
			)
		}
	}

	@JvmStatic
	fun onRenderWorld(stack: MatrixStack, projectionMatrix: Matrix4f, tickDelta: Float) {
		val world = Game.world ?: return
		val modelViewMatrix = stack.peek().positionMatrix

		processPing(tickDelta)

		val time = world.time.toInt()

		for (ping in pingRepo) {
			if (ping.uuid != null) {
				val ent = world.entities.find { entity -> entity.uuid == ping.uuid }

				if (ent != null) {
					if (ent.type == EntityType.ITEM && config.itemIconVisible) {
						val itemEnt = ent as ItemEntity
						ping.itemStack = itemEnt.stack.copy()
						ping.color = ColorHelper.Argb.getArgb(255, 170, 170, 170)
					}
					else if (ent.type == EntityType.PLAYER) {
						ping.color = ColorHelper.Argb.getArgb(255, 69, 181, 255)
					}
					else if (ent is Monster) {
						ping.color = ColorHelper.Argb.getArgb(255, 255, 80, 80)
					}
					else if (ent is Angerable) {
						ping.color = ColorHelper.Argb.getArgb(255, 255, 187, 85)
					}
					else if (ent is MobEntity) {
						ping.color = ColorHelper.Argb.getArgb(255, 69, 255, 69)
					}

					ping.pos = ent.getLerpedPos(tickDelta).add(0.0, ent.boundingBox.yLength, 0.0)
				}
			}

			ping.screenPos = Math.project3Dto2D(ping.pos, modelViewMatrix, projectionMatrix)
			ping.aliveTime = time - ping.spawnTime
		}

		if (pingRepo.count() > config.pingMaxCount) {
			pingRepo.removeAll(pingRepo.sortedBy { it.aliveTime }.subList(config.pingMaxCount, pingRepo.count()))
		}

		pingRepo.removeIf { p -> p.aliveTime!! > config.pingDuration * TPS }
	}

	@JvmStatic
	fun onRenderGUI(stack: MatrixStack, ci: CallbackInfo) {
		for (ping in pingRepo) {
			val uiScale = Game.window.scaleFactor

			val pingPosScreen = ping.screenPos ?: continue
			val cameraPosVec = Game.player?.getCameraPosVec(Game.tickDelta) ?: continue
			val distanceToPing = cameraPosVec.distanceTo(ping.pos).toFloat()

			val pingColor = ping.color
			val shadowBlack = ColorHelper.Argb.getArgb(85, 0, 0, 0)

			stack.push() // push

			stack.translate((pingPosScreen.x / uiScale), (pingPosScreen.y / uiScale), 0.0)
			stack.scale(1f, 1f, 1f)

			stack.push() // push text

			val distanceText = "%.1fm".format(distanceToPing)
			val distanceTextMetrics = Vec2f(
				Game.textRenderer.getWidth(distanceText).toFloat(),
				Game.textRenderer.fontHeight.toFloat()
			)
			var distanceTextOffset = distanceTextMetrics.multiply(-0.5f).add(Vec2f(0f, distanceTextMetrics.y * -1f))
			distanceTextOffset = Vec2f(distanceTextOffset.x.roundToInt().toFloat(), distanceTextOffset.y.roundToInt().toFloat())

			stack.translate(distanceTextOffset.x.toDouble(), distanceTextOffset.y.toDouble(), 0.0)

			DrawableHelper.fill(stack, -2, -2, distanceTextMetrics.x.toInt() + 1, distanceTextMetrics.y.toInt(), shadowBlack)
			Game.textRenderer.drawWithShadow(stack, distanceText, 0f, 0f, pingColor)

			stack.pop() // pop text

			stack.push() // push text

			val usernameText = ping.username
			val usernameTextMetrics = Vec2f(
					Game.textRenderer.getWidth(usernameText).toFloat(),
					Game.textRenderer.fontHeight.toFloat()
			)
			var usernameTextOffset = usernameTextMetrics.multiply(-0.5f).add(Vec2f(-1f, usernameTextMetrics.y * -4f))
			usernameTextOffset = Vec2f(usernameTextOffset.x.roundToInt().toFloat(), usernameTextOffset.y.roundToInt().toFloat())

			stack.scale(0.5f, 0.5f, 0.5f)
			stack.translate(usernameTextOffset.x.toDouble(), usernameTextOffset.y.toDouble(), 0.0)

			DrawableHelper.fill(stack, -2, -2, usernameTextMetrics.x.toInt() + 1, usernameTextMetrics.y.toInt(), shadowBlack)
			Game.textRenderer.drawWithShadow(stack, usernameText, 0f, 0f, pingColor)

			stack.pop() // pop text

			if (ping.itemStack != null && config.itemIconVisible) {
				val model = Game.itemRenderer.getModel(ping.itemStack, null, null, 0)

				Draw.renderGuiItemModel(
					ping.itemStack,
					(pingPosScreen.x / uiScale),
					(pingPosScreen.y / uiScale),
					model,
					stack,
					0.5f
				)
			} else {
				stack.rotateZ(PI.toFloat() / 4f)
				stack.translate(-2.0, -2.0, 0.0)
				DrawableHelper.fill(stack, 0, 0, 4, 4, shadowBlack)
				stack.translate(0.5, 0.5, 0.0)
				DrawableHelper.fill(stack, 0, 0, 3, 3, pingColor)
			}

			stack.pop() // pop
		}
	}
}
