package net.floodlightcontroller.core.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.types.DatapathId;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.routing.FlowRouteInfo;

public interface IOFReservationUpdate extends IFloodlightService {
    
	void setPathReservationStateForUpdateId(int updateId, SwitchVoteState state);
    
	SwitchVoteState getSwitchVoteState(DatapathId sw, int updateId);
	
	void addPathReservationAttempt(int updateId, HashSet<DatapathId> switchList, FlowRouteInfo[] routeToInstall);
	
	Set<DatapathId> getSwitchesForUpdateId(int updateId);
	
	FlowRouteInfo[] getNetworkUpdate(int updateId);
	
	void updateIdReattemptCounter(int updateId);

	void addFirstPacketIn(Integer integer, IOFSwitch sw, OFPacketIn pi);

	HashMap<OFPacketIn, IOFSwitch> getFirstPacketIn(int updateId);
}
