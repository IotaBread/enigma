package org.quiltmc.enigma.network.packet;

import org.quiltmc.enigma.network.ClientPacketHandler;
import org.quiltmc.enigma.api.translation.mapping.EntryChange;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class EntryChangeS2CPacket implements Packet<ClientPacketHandler> {
	private int syncId;
	private EntryChange<?> change;

	public EntryChangeS2CPacket(int syncId, EntryChange<?> change) {
		this.syncId = syncId;
		this.change = change;
	}

	EntryChangeS2CPacket() {
	}

	@Override
	public void read(DataInput input) throws IOException {
		this.syncId = input.readUnsignedShort();
		this.change = PacketHelper.readEntryChange(input);
	}

	@Override
	public void write(DataOutput output) throws IOException {
		output.writeShort(this.syncId);
		PacketHelper.writeEntryChange(output, this.change);
	}

	@Override
	public void handle(ClientPacketHandler handler) {
		if (handler.applyChangeFromServer(this.change)) {
			handler.sendPacket(new ConfirmChangeC2SPacket(this.syncId));
		}
	}
}
