package net.floodlightcontroller.core.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.debugevent.IDebugEventService;
import net.floodlightcontroller.routing.FlowRouteInfo;
import org.projectfloodlight.openflow.protocol.OFBundleCtrlMsg;
import org.projectfloodlight.openflow.protocol.OFBundleCtrlType;
import org.projectfloodlight.openflow.protocol.OFBundleFailedCode;
import org.projectfloodlight.openflow.protocol.OFErrorMsg;
import org.projectfloodlight.openflow.protocol.OFErrorType;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.errormsg.OFBundleFailedErrorMsg;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFErrorCauseData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OFReservationUpdate implements IOFReservationUpdate, IFloodlightModule, IOFMessageListener {
	protected static Logger logger = LoggerFactory.getLogger(OFReservationUpdate.class);
	
	// private ISyncService syncService;
	public static final String MODULE_NAME = "reservationupdate";
	
	protected static final int NUMBER_OF_RETRY_ATTEMPTS = 0;
	
	// Module Dependencies
	IFloodlightProviderService floodlightProvider;
	IDebugEventService debugEventService;
	IDebugCounterService debugCounterService;
	IOFSwitchService switchService;
	IOFAtomicUpdate atomicService;
	
	protected static HashMap<Integer, FlowRouteInfo[]> updateId2ControlLogicMap = new HashMap<Integer, FlowRouteInfo[]>();
	protected static HashMap<Integer, HashMap<DatapathId, SwitchVoteState> > updateId2SwitchStateMap = new HashMap<Integer, HashMap<DatapathId, SwitchVoteState> >();
	protected static HashMap<Integer, Integer> updateIdReattemptCounter = new HashMap<Integer, Integer>();
	protected static HashMap<Integer, OFPacketIn> updateId2PacketInMap = new HashMap<Integer, OFPacketIn>();
	protected static HashMap<Integer, IOFSwitch> updateId2SwitchMap = new HashMap<Integer, IOFSwitch>();
	
	protected static List<Integer> successfullyInstalled = new ArrayList<Integer>();
	protected static List<Integer> rollbackMap = new ArrayList<Integer>();
	
	public OFReservationUpdate() {
	}
	
	protected Command processErrorMessage(IOFSwitch sw, OFErrorMsg msg, FloodlightContext cntx) {
		if (msg.getErrType() == OFErrorType.BAD_ACTION) {
			// System.out.println("ERROR message received as a response to the attempt of configuration changes over a claimed switch");
			return Command.STOP;
		}
		
		// Bundle installation failed
		if (msg.getErrType() == OFErrorType.BUNDLE_FAILED) {
			OFBundleFailedErrorMsg bundleFailed = (OFBundleFailedErrorMsg) msg;
			
			OFBundleFailedCode code = bundleFailed.getCode();
			OFErrorCauseData data = bundleFailed.getData();
			String errorString = data.toString();
			
			if (code == OFBundleFailedCode.TIMEOUT) {
				return Command.STOP;
			}
			
			String left = "bundleId=";
			String right = ", bundleCtrlType";
			
			errorString = errorString.substring(errorString.indexOf(left) + left.length());
			errorString = errorString.substring(0, errorString.indexOf(right));
			
			int updateId = Integer.parseInt(errorString);
						
			if (addRollback(updateId)) {
				if (getNetworkUpdate(updateId) != null && !isUpdateObsolate(updateId)) {
					setPathReservationStateForUpdateId(updateId, SwitchVoteState.OBSOLETE);
					commitNetworkUpdate(updateId, false);
				}
			} else {
				// System.out.println("Racing conditions - rollback! Update ID: " + updateId);
			}
		}
		
		return Command.CONTINUE;
	}
	
	private boolean addRollback(int updateId) {
		if (!rollbackMap.contains(updateId)) {
			rollbackMap.add(updateId);
			return true;
		} else {
			return false;
		}
	}

	protected Command processBundleControlMessage(IOFSwitch sw, OFBundleCtrlMsg msg, FloodlightContext cntx) {
		if (msg.getBundleCtrlType() == OFBundleCtrlType.COMMIT_REPLY) {
			int updateId = msg.getBundleId().getInt();
			
			if (updateVoteLockStateForSwitch(updateId, sw.getId(), SwitchVoteState.CONFIRM)) {
				return Command.STOP;
			}
		}
		return Command.CONTINUE;
	}
	
	public class commitNetworkUpdate implements Runnable {
		int updateId;
		boolean commit;
		
		public commitNetworkUpdate(int updateId, boolean commit) {
			this.updateId = updateId;
			this.commit = commit;
		}
		
		public void run() {
			atomicService.commitNetworkUpdate(updateId, commit);
		}
	}
	
	private void commitNetworkUpdate(int updateId, boolean commit) {
		Thread thread = new Thread(new commitNetworkUpdate(updateId, commit));
		thread.setPriority(Thread.MIN_PRIORITY);
		atomicService.scheduleTask(thread,0);
	}
	
	public Set<DatapathId> getSwitchesForUpdateId(int updateId) {
		HashMap<DatapathId,SwitchVoteState> switchReservationStateMap = updateId2SwitchStateMap.get(updateId);
		
		return switchReservationStateMap.keySet();
	}
	
	public FlowRouteInfo[] getNetworkUpdate(int updateId) {
		return updateId2ControlLogicMap.get(updateId);
	}
	
	public boolean isUpdateObsolate(int updateId) {
		SwitchVoteState switchReservationState = null;
		HashMap<DatapathId,SwitchVoteState> switchReservationStateMap = updateId2SwitchStateMap.get(updateId);
		if (switchReservationStateMap != null) {
			Set<DatapathId> swSet = switchReservationStateMap.keySet();
			for(DatapathId swId:swSet) {
				switchReservationState = updateId2SwitchStateMap.get(updateId).get(swId);
				break;
			}
		}
		
		if (switchReservationState == SwitchVoteState.OBSOLETE) {
			return true;
		} else {
			return false;
		}
	}
	
	public void addPathReservationAttempt(int updateId, HashSet<DatapathId> switchList, FlowRouteInfo[] routeToInstall) {
		
		if (updateIdReattemptCounter.get(updateId) == null) {
			updateIdReattemptCounter.put(updateId, 0);
			updateId2ControlLogicMap.put(updateId, routeToInstall);
			updateId2SwitchStateMap.put(updateId, null);
		}
		
		HashMap<DatapathId, SwitchVoteState> switchReservationStateMap = new HashMap<DatapathId, SwitchVoteState>();
		for (DatapathId swId : switchList) {
			switchReservationStateMap.put(swId, SwitchVoteState.IDLE);
		}
		
		updateId2SwitchStateMap.put(updateId, switchReservationStateMap);
	}
	
	public boolean updateVoteLockStateForSwitch(int updateId, DatapathId sw, SwitchVoteState state) {
		SwitchVoteState switchCurrentReservationState = getSwitchVoteState(sw, updateId);
		if (switchCurrentReservationState == SwitchVoteState.OBSOLETE) {
			// System.out.println("Race conditions - prevention 1! Token: " + updateId);
			return true;
		}
		
		if (switchCurrentReservationState == state) {
			// System.out.println("Race conditions - prevention 2! Token: " + updateId);
			return true;
		}
		
		if (updateId2SwitchStateMap.get(updateId) != null) {
			updateId2SwitchStateMap.get(updateId).put(sw, state);
			
			if (checkVoteLockState(updateId)) {
				commitNetworkUpdate(updateId, true);
			}
			
			return true;
		}
		
		return false;
	}
	
	public void setPathReservationStateForUpdateId(int updateId, SwitchVoteState state) {
		HashMap<DatapathId,SwitchVoteState> switchReservationStateMap = updateId2SwitchStateMap.get(updateId);
		
		if (switchReservationStateMap != null) {
			Set<DatapathId> swSet = switchReservationStateMap.keySet();
			
			for(DatapathId swId:swSet) {
				updateId2SwitchStateMap.get(updateId).put(swId, state);
			}
		}
	}
	
	// This method is checking if all switches affected by the update (or only affected by the first leg of the update)
	// replied with confirm
	private boolean checkVoteLockState(int updateId) {
		HashMap<DatapathId, SwitchVoteState> switchReservationStateMap = updateId2SwitchStateMap.get(updateId);
		Set<DatapathId> swList = switchReservationStateMap.keySet();
		// System.out.println("(checkVoteLockState) | switch list: " + swList.toString());
		
		for (DatapathId sw:swList) {
			if(switchReservationStateMap.get(sw) != SwitchVoteState.CONFIRM) {
				return false;
			}
		}
		
		setPathReservationStateForUpdateId(updateId, SwitchVoteState.OBSOLETE);
		return true;
	}
	
	public SwitchVoteState getSwitchVoteState(DatapathId sw, int updateId) {
		if (updateId2SwitchStateMap.get(updateId) != null) {
			return updateId2SwitchStateMap.get(updateId).get(sw);
		}
		
		// System.out.println("ERROR! No token " + token + " recorded!");	
		return null;
	}
	
	public void updateIdReattemptCounter(int updateId) {
		Integer counter = updateIdReattemptCounter.get(updateId);
		updateIdReattemptCounter.put(updateId, counter+1);
	}
	
	public boolean checkUpdateIdReatemptCount(int updateId) {
		if (updateIdReattemptCounter.get(updateId) <= NUMBER_OF_RETRY_ATTEMPTS) {
			return true;
		}
		
		return false;
	}
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IOFReservationUpdate.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>,
		IFloodlightService> m =
		new HashMap<Class<? extends IFloodlightService>,
		IFloodlightService>();
		// We are the class that implements the service
		m.put(IOFReservationUpdate.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(IDebugEventService.class);
		l.add(IDebugCounterService.class);
		l.add(IOFSwitchService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		debugEventService = context.getServiceImpl(IDebugEventService.class);
		debugCounterService = context.getServiceImpl(IDebugCounterService.class);
		atomicService = context.getServiceImpl(IOFAtomicUpdate.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider.addOFMessageListener(OFType.ERROR, this);
		floodlightProvider.addOFMessageListener(OFType.BUNDLE_CONTROL, this);
	}

	@Override
	public String getName() {
		return MODULE_NAME;
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return ((type.equals(OFType.PACKET_IN) || type.equals(OFType.ERROR)) && (name.equals("topology") || 
                name.equals("devicemanager") || name.equals("virtualizer")) || name.equals("forwarding"));
	}

	public Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		
		switch (msg.getType()) {
			case ERROR:
				return this.processErrorMessage(sw, (OFErrorMsg) msg, cntx);
			case BUNDLE_CONTROL:
				return this.processBundleControlMessage(sw, (OFBundleCtrlMsg)msg, cntx);
			default:
				break;
		}
		return Command.CONTINUE;
	}

	@Override
	public void addFirstPacketIn(Integer integer, IOFSwitch sw, OFPacketIn pi) {
		updateId2SwitchMap.put(integer, sw);
		updateId2PacketInMap.put(integer, pi);
	}

	@Override
	public HashMap<OFPacketIn, IOFSwitch> getFirstPacketIn(int updateId) {
		HashMap<OFPacketIn, IOFSwitch> firstPacketIn = new HashMap<OFPacketIn, IOFSwitch>();
		firstPacketIn.put(updateId2PacketInMap.get(updateId), updateId2SwitchMap.get(updateId));
		
		return firstPacketIn;
	}
	
}
