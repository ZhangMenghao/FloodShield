package net.floodlightcontroller.statistics;

import com.google.common.primitives.UnsignedLong;
import com.google.common.util.concurrent.ListenableFuture;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.statistics.StatisticsCollector.HostStatsCollector;
import net.floodlightcontroller.statistics.web.SwitchStatisticsWebRoutable;
import net.floodlightcontroller.threadpool.IThreadPoolService;
import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.ver13.OFMeterSerializerVer13;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.Thread.State;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class StatisticsCollector implements IFloodlightModule, IStatisticsService {
	private static final Logger log = LoggerFactory.getLogger(StatisticsCollector.class);

	private static IOFSwitchService switchService;
	private static IThreadPoolService threadPoolService;
	private static IRestApiService restApiService;

	private static int portStatsInterval = 3; /* could be set by REST API, so not final */

	private static final HashMap<NodePortTuple, SwitchPortBandwidth> portStats = new HashMap<NodePortTuple, SwitchPortBandwidth>();
	private static final HashMap<NodePortTuple, SwitchPortBandwidth> tentativePortStats = new HashMap<NodePortTuple, SwitchPortBandwidth>();
	
	// FT-Guard part
	private static boolean isEnabled = true;
	public static int hostStatsInterval = 3;
	private static ScheduledFuture<?> hostStatsCollector;
	public static final ShieldManager shieldManager = new ShieldManager();
	public static final HashMap<IPv4Address, HostEntry> hostFlowMap
		= new HashMap<IPv4Address, HostEntry>();
	public static final HashMap<IPv4Address, DatapathId> hostDpMap
		= new HashMap<IPv4Address, DatapathId>();
	
	/**
	 * Run periodically to collect all port statistics. This only collects
	 * bandwidth stats right now, but it could be expanded to record other
	 * information as well. The difference between the most recent and the
	 * current RX/TX bytes is used to determine the "elapsed" bytes. A
	 * timestamp is saved each time stats results are saved to compute the
	 * bits per second over the elapsed time. There isn't a better way to
	 * compute the precise bandwidth unless the switch were to include a
	 * timestamp in the stats reply message, which would be nice but isn't
	 * likely to happen. It would be even better if the switch recorded
	 * bandwidth and reported bandwidth directly.
	 *
	 * Stats are not reported unless at least two iterations have occurred
	 * for a single switch's reply. This must happen to compare the byte
	 * counts and to get an elapsed time.
	 *
	 * @author Ryan Izard, ryan.izard@bigswitch.com, rizard@g.clemson.edu
	 *
	 */

	public class HostStatsCollector implements Runnable {
		@Override
		public void run() {
			try {
				log.debug("====================================");
				Map<DatapathId, List<OFStatsReply>> replies = getSwitchStatistics(switchService.getAllSwitchDpids(), OFStatsType.FLOW);
				for (IPv4Address ip : hostFlowMap.keySet())
					hostFlowMap.get(ip).init();
				for (Entry<DatapathId, List<OFStatsReply>> e : replies.entrySet()) {
					String dpId = e.getKey().toString();
					for (OFStatsReply reply : e.getValue()) {
						OFFlowStatsReply fsr = (OFFlowStatsReply) reply;
						for (OFFlowStatsEntry pse : fsr.getEntries()) {
							Match match = pse.getMatch();
							IPv4Address ip = null;
							if (!pse.getTableId().toString().equals("0x0"))
								continue;
							if (pse.getHardTimeout() == 0 && pse.getIdleTimeout() == 0)
								continue;
							try {
								ip = match.get(MatchField.IPV4_SRC);
								if (hostDpMap.get(ip).toString().equals(dpId))
									hostFlowMap.get(ip).addHighCount(pse);
							} catch (NullPointerException exception) {}
						}
					}
				}

				for (IPv4Address ip : hostFlowMap.keySet()) {
					hostFlowMap.get(ip).compute();
					log.debug("######HOST " + ip.toString() + ": " + hostFlowMap.get(ip).toString());
				}
			}
			catch (Exception e) {
				// TODO: handle exception
				log.debug("$$$$$$$$$$$$$$$$$$$");
				log.debug(e.toString());
			}
		}
	}
	
	/**
	 * Single thread for collecting switch statistics and
	 * containing the reply.
	 *
	 * @author Ryan Izard, ryan.izard@bigswitch.com, rizard@g.clemson.edu
	 *
	 */
	private class GetStatisticsThread extends Thread {
		private List<OFStatsReply> statsReply;
		private DatapathId switchId;
		private OFStatsType statType;

		public GetStatisticsThread(DatapathId switchId, OFStatsType statType) {
			this.switchId = switchId;
			this.statType = statType;
			this.statsReply = null;
		}

		public List<OFStatsReply> getStatisticsReply() {
			return statsReply;
		}

		public DatapathId getSwitchId() {
			return switchId;
		}

		@Override
		public void run() {
			statsReply = getSwitchStatistics(switchId, statType);
		}
	}

	/*
	 * IFloodlightModule implementation
	 */

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IStatisticsService.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m =
				new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		m.put(IStatisticsService.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IOFSwitchService.class);
		l.add(IThreadPoolService.class);
		l.add(IRestApiService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		switchService = context.getServiceImpl(IOFSwitchService.class);
		threadPoolService = context.getServiceImpl(IThreadPoolService.class);
		restApiService = context.getServiceImpl(IRestApiService.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		restApiService.addRestletRoutable(new SwitchStatisticsWebRoutable());
		if (isEnabled) {
			startStatisticsCollection();
		}
	}

	/*
	 * IStatisticsService implementation
	 */

	@Override
	public SwitchPortBandwidth getBandwidthConsumption(DatapathId dpid, OFPort p) {
		return portStats.get(new NodePortTuple(dpid, p));
	}


	@Override
	public Map<NodePortTuple, SwitchPortBandwidth> getBandwidthConsumption() {
		return Collections.unmodifiableMap(portStats);
	}

	@Override
	public synchronized void collectStatistics(boolean collect) {
		if (collect && !isEnabled) {
			startStatisticsCollection();
			isEnabled = true;
		} else if (!collect && isEnabled) {
			stopStatisticsCollection();
			isEnabled = false;
		}
		/* otherwise, state is not changing; no-op */
	}

	/*
	 * Helper functions
	 */

	/**
	 * Start all stats threads.
	 */
	private void startStatisticsCollection() {
		hostStatsCollector = threadPoolService.getScheduledExecutor().scheduleAtFixedRate(new HostStatsCollector(), hostStatsInterval, hostStatsInterval, TimeUnit.SECONDS);
		tentativePortStats.clear(); /* must clear out, otherwise might have huge BW result if present and wait a long time before re-enabling stats */
		log.warn("Statistics collection thread(s) started");
	}

	/**
	 * Stop all stats threads.
	 */
	private void stopStatisticsCollection() {
		if (!hostStatsCollector.cancel(false)) {
			log.error("Could not cancel port stats thread");
		} else {
			log.warn("Statistics collection thread(s) stopped");
		}
	}

	/**
	 * Retrieve the statistics from all switches in parallel.
	 * @param dpids
	 * @param statsType
	 * @return
	 */
	private Map<DatapathId, List<OFStatsReply>> getSwitchStatistics(Set<DatapathId> dpids, OFStatsType statsType) {
		HashMap<DatapathId, List<OFStatsReply>> model = new HashMap<DatapathId, List<OFStatsReply>>();

		List<GetStatisticsThread> activeThreads = new ArrayList<GetStatisticsThread>(dpids.size());
		List<GetStatisticsThread> pendingRemovalThreads = new ArrayList<GetStatisticsThread>();
		GetStatisticsThread t;
		for (DatapathId d : dpids) {
			t = new GetStatisticsThread(d, statsType);
			activeThreads.add(t);
			t.start();
		}

		/* Join all the threads after the timeout. Set a hard timeout
		 * of 12 seconds for the threads to finish. If the thread has not
		 * finished the switch has not replied yet and therefore we won't
		 * add the switch's stats to the reply.
		 */
		for (int iSleepCycles = 0; iSleepCycles < portStatsInterval; iSleepCycles++) {
			for (GetStatisticsThread curThread : activeThreads) {
				if (curThread.getState() == State.TERMINATED) {
					model.put(curThread.getSwitchId(), curThread.getStatisticsReply());
					pendingRemovalThreads.add(curThread);
				}
			}

			/* remove the threads that have completed the queries to the switches */
			for (GetStatisticsThread curThread : pendingRemovalThreads) {
				activeThreads.remove(curThread);
			}

			/* clear the list so we don't try to double remove them */
			pendingRemovalThreads.clear();

			/* if we are done finish early */
			if (activeThreads.isEmpty()) {
				break;
			}

			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				log.error("Interrupted while waiting for statistics", e);
			}
		}

		return model;
	}

	/**
	 * Get statistics from a switch.
	 * @param switchId
	 * @param statsType
	 * @return
	 */
	@SuppressWarnings("unchecked")
	protected List<OFStatsReply> getSwitchStatistics(DatapathId switchId, OFStatsType statsType) {
		IOFSwitch sw = switchService.getSwitch(switchId);
		ListenableFuture<?> future;
		List<OFStatsReply> values = null;
		Match match;
		if (sw != null) {
			OFStatsRequest<?> req = null;
			switch (statsType) {
				case FLOW:
					match = sw.getOFFactory().buildMatch().build();
					req = sw.getOFFactory().buildFlowStatsRequest()
							.setMatch(match)
							.setOutPort(OFPort.ANY)
							.setTableId(TableId.ALL)
							.setOutGroup(OFGroup.ANY)
							.build();
					break;
				case AGGREGATE:
					match = sw.getOFFactory().buildMatch().build();
					req = sw.getOFFactory().buildAggregateStatsRequest()
							.setMatch(match)
							.setOutPort(OFPort.ANY)
							.setTableId(TableId.ALL)
							.build();
					break;
				case PORT:
					req = sw.getOFFactory().buildPortStatsRequest()
							.setPortNo(OFPort.ANY)
							.build();
					break;
				case QUEUE:
					req = sw.getOFFactory().buildQueueStatsRequest()
							.setPortNo(OFPort.ANY)
							.setQueueId(UnsignedLong.MAX_VALUE.longValue())
							.build();
					break;
				case DESC:
					req = sw.getOFFactory().buildDescStatsRequest()
							.build();
					break;
				case GROUP:
					if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) > 0) {
						req = sw.getOFFactory().buildGroupStatsRequest()
								.build();
					}
					break;

				case METER:
					if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_13) >= 0) {
						req = sw.getOFFactory().buildMeterStatsRequest()
								.setMeterId(OFMeterSerializerVer13.ALL_VAL)
								.build();
					}
					break;

				case GROUP_DESC:
					if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) > 0) {
						req = sw.getOFFactory().buildGroupDescStatsRequest()
								.build();
					}
					break;

				case GROUP_FEATURES:
					if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) > 0) {
						req = sw.getOFFactory().buildGroupFeaturesStatsRequest()
								.build();
					}
					break;

				case METER_CONFIG:
					if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_13) >= 0) {
						req = sw.getOFFactory().buildMeterConfigStatsRequest()
								.build();
					}
					break;

				case METER_FEATURES:
					if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_13) >= 0) {
						req = sw.getOFFactory().buildMeterFeaturesStatsRequest()
								.build();
					}
					break;

				case TABLE:
					if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) > 0) {
						req = sw.getOFFactory().buildTableStatsRequest()
								.build();
					}
					break;

				case TABLE_FEATURES:
					if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_10) > 0) {
						req = sw.getOFFactory().buildTableFeaturesStatsRequest()
								.build();
					}
					break;
				case PORT_DESC:
					if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_13) >= 0) {
						req = sw.getOFFactory().buildPortDescStatsRequest()
								.build();
					}
					break;
				case EXPERIMENTER:
				default:
					log.error("Stats Request Type {} not implemented yet", statsType.name());
					break;
			}

			try {
				if (req != null) {
					future = sw.writeStatsRequest(req);
					values = (List<OFStatsReply>) future.get(portStatsInterval / 2, TimeUnit.SECONDS);
				}
			} catch (Exception e) {
				log.error("Failure retrieving statistics from switch {}. {}", sw, e);
			}
		}
		return values;
	}
}