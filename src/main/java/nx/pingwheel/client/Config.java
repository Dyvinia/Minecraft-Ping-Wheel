package nx.pingwheel.client;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Setter;

@Setter
@ToString
@EqualsAndHashCode
public class Config {
	int pingVolume = 100;
	int pingDistance = 150;
	boolean itemIconVisible = true;
	int pingDuration = 7;
	int pingMaxCount = 8;
	String channel = "";

	// manual getters for kotlin compatibility

	public int getPingVolume() {
		return pingVolume;
	}

	public int getPingDistance() {
		return pingDistance;
	}

	public boolean isItemIconVisible() {
		return itemIconVisible;
	}

	public int getPingDuration() {
		return pingDuration;
	}

	public int getPingMaxCount() {
		return pingMaxCount;
	}

	public String getChannel() {
		return channel;
	}
}
