package net.floodlightcontroller.core.internal;

import java.util.List;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.routing.FlowRouteInfo;

public interface IOFAtomicUpdate extends IFloodlightService {
	
	void voteLock(List<FlowRouteInfo[]> routeToInstall, int token, IOFSwitch sw, OFPacketIn pi);
    
	void commitNetworkUpdate(int token, boolean commit);
    
    void scheduleTask(Thread task, long delay);
}
