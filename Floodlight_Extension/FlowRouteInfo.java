package net.floodlightcontroller.routing;

import java.util.LinkedHashSet;
import java.util.List;

import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.NodePortTuple;

import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;

public class FlowRouteInfo{

	public Route route;
	public Match match;
	public DatapathId srcSW;
	public OFPort apSrcSW;
	public DatapathId dstSW;
	public OFPort apDstSW;

	public LinkedHashSet<DatapathId> getCoreSwitchList() {
		// System.out.println("Route: " + route);
		List<NodePortTuple> switchPortList = this.route.getPath();
		LinkedHashSet<DatapathId> switchList = new LinkedHashSet<DatapathId>();

		for (int indx = 0; indx < switchPortList.size() - 1; indx += 2) {
			DatapathId switchDPID = switchPortList.get(indx).getNodeId();
			
			if (switchDPID.toString().contains("0a:00:00")) {
				continue;
			}
			
			switchList.add(switchDPID);
		}
		
		// work-around to achieve updates of path length = 1
		/* DatapathId last = null;
		for (DatapathId sw : switchList) {
			last = sw;
		}
		switchList.remove(last); */
		
		return switchList;
	}
	
	public LinkedHashSet<DatapathId> getAccessSwitchList() {
		// System.out.println("Route: " + route);
		List<NodePortTuple> switchPortList = this.route.getPath();
		LinkedHashSet<DatapathId> switchList = new LinkedHashSet<DatapathId>();

		for (int indx = 0; indx < switchPortList.size() - 1; indx += 2) {
			DatapathId switchDPID = switchPortList.get(indx).getNodeId();
			
			if (switchDPID.toString().contains("0c:00:00")) {
				continue;
			}
			
			switchList.add(switchDPID);
		}
		
		return switchList;
	}
	
	public String getSourceDestinationString() {
		return this.srcSW + "->" + this.dstSW;
	}
}
