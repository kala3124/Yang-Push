/*
 * Copyright © 2016 Cisco Systems Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangpushserver.subscription;

import java.util.HashMap;
import java.util.Map;

import org.opendaylight.controller.config.util.xml.XmlUtil;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMDataWriteTransaction;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.Encodings;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.Subscriptions;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.subscriptions.Subscription;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.subscriptions.subscription.FilterType1;
import org.opendaylight.yangpushserver.rpc.RpcImpl;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifierWithPredicates;
import org.opendaylight.yangtools.yang.data.api.schema.ChoiceNode;
import org.opendaylight.yangtools.yang.data.api.schema.ContainerNode;
import org.opendaylight.yangtools.yang.data.api.schema.MapEntryNode;
import org.opendaylight.yangtools.yang.data.api.schema.MapNode;
import org.opendaylight.yangtools.yang.data.impl.schema.Builders;
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * This singleton class will manage and process all subscriptions.
 * 
 * @author Philipp Konegen
 *
 */
public class SubscriptionEngine {

	private static final Logger LOG = LoggerFactory.getLogger(SubscriptionEngine.class);

	// Namespaces for different Moduls
	public static final String YP_NS = "urn:ietf:params:xml:ns:yang:ietf-yang-push";
	public static final String YP_NS_DATE = "2016-06-15";
	public static final String NOTIF_BIS = "urn:ietf:params:xml:ns:yang:ietf-event-notifications";
	public static final String NOTIF_BIS_DATE = "2016-06-15";

	public static final QName N_SUBTREE_FILTER_TYPE1_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "filter-type-1");

	public static final QName Y_UPDATE_FILTER_NAME = QName.create(YP_NS, YP_NS_DATE, "update-filter");
	public static final QName N_SUBTREE_FILTER_TYPE_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "filter-type");
	public static final QName N_FILTER_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "filter");
	public static final QName N_FILT_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "filt");
	public static final QName Y_SUBTREE_FILTER_NAME = QName.create(YP_NS, YP_NS_DATE, "subtree-filter");
	public static final QName N_SUB_ID_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "subscription-id");
	public static final QName Y_DAMPENING_PERIOD_NAME = QName.create(YP_NS, YP_NS_DATE, "dampening-period");
	public static final QName Y_PERIOD_NAME = QName.create(YP_NS, YP_NS_DATE, "period");
	public static final QName Y_UPDATE_TRIGGER_NAME = QName.create(YP_NS, YP_NS_DATE, "update-trigger");
	public static final QName Y_SUB_DEPENDENCY_NAME = QName.create(YP_NS, YP_NS_DATE, "subscription-dependency");
	public static final QName Y_SUB_PRIORITY_NAME = QName.create(YP_NS, YP_NS_DATE, "subscription-priority");
	public static final QName Y_NO_SYNCH_ON_START_NAME = QName.create(YP_NS, YP_NS_DATE, "no-synch-on-start");
	public static final QName Y_EXCLUDED_CHANGE_NAME = QName.create(YP_NS, YP_NS_DATE, "excluded-change");

	// global data broker
	private DOMDataBroker globalDomDataBroker = null;
	// self instance
	private static SubscriptionEngine instance = null;
	// Subscription ID sid
	private static int sub_id = -1;
	// map of subscriptions
	private Map<String, SubscriptionInfo> masterSubMap = null;

	/**
	 * The selected operation is used to update a subscription stored in MD-SAL
	 *
	 */
	public static enum operations {
		establish, delete, modify,
	}

	/**
	 * Creating protected constructor for creating singleton instance
	 */
	protected SubscriptionEngine() {
		super();
		masterSubMap = new HashMap<String, SubscriptionInfo>();
	}

	/**
	 * getInstance method implements subscription engine as singleton
	 * 
	 * @return this
	 */
	public static SubscriptionEngine getInstance() {
		if (instance == null) {
			instance = new SubscriptionEngine();
		}
		return instance;
	}

	/**
	 * Set global BI data broker to subscription engine
	 * 
	 * @param globalDomDataBroker
	 */
	public void setDataBroker(DOMDataBroker globalDomDataBroker) {
		this.globalDomDataBroker = globalDomDataBroker;
	}

	/**
	 * This method is called by {@link RpcImpl} and generates the individual Id
	 * for a subscription.
	 * 
	 */
	public String generateSubscriptionId() {
		if (Integer.toString(sub_id).equals("-1")) {
			sub_id = 1;
			return Integer.toString(sub_id);
		}
		this.sub_id++;
		return Integer.toString(this.sub_id);
	}

	/**
	 * Creates initial part of data store where the subscriptions will be stored
	 * 
	 */
	public void createSubscriptionDataStore() {
		DOMDataWriteTransaction tx = this.globalDomDataBroker.newWriteOnlyTransaction();
		NodeIdentifier subscriptions = NodeIdentifier.create(Subscriptions.QNAME);
		NodeIdentifier subscription = NodeIdentifier.create(Subscription.QNAME);

		YangInstanceIdentifier iid = YangInstanceIdentifier.builder().node(Subscriptions.QNAME).build();
		// Creates container node push-update in BI way and
		// commit to MD-SAL at the start of the application.
		ContainerNode cn = Builders.containerBuilder().withNodeIdentifier(subscriptions).build();
		tx.merge(LogicalDatastoreType.OPERATIONAL, iid, cn);
		LOG.info("Transaction going to submit");
		try {
			tx.submit().checkedGet();
		} catch (TransactionCommitFailedException e1) {
			e1.printStackTrace();
		}
		// Creates push-update list node and BI way and
		// commit to MD-SAL at the start of the application.
		YangInstanceIdentifier iid_1 = iid.node(Subscription.QNAME);
		MapNode mn = Builders.mapBuilder().withNodeIdentifier(subscription).build();
		DOMDataWriteTransaction tx_1 = this.globalDomDataBroker.newWriteOnlyTransaction();
		tx_1.merge(LogicalDatastoreType.OPERATIONAL, iid_1, mn);
		try {
			tx_1.submit().checkedGet();
		} catch (TransactionCommitFailedException e1) {
			e1.printStackTrace();
		}
	}

	/**
	 * This method is called by {@link RpcImpl} whenever the MD-SAL data store
	 * or the local masterSubMap needs to updated. This occurs after
	 * establishing, deleting or modifying a subscription.
	 * 
	 * @param SubscriptionInfo
	 *            Informations of the subscription, which should be stored or
	 *            deleted.
	 * @param type
	 *            Distinguishes if the subscription should be established,
	 *            deleted or modified.
	 * 
	 */
	// Infos need to be stored to MD-SAL and locally.
	public void updateMdSal(SubscriptionInfo subscriptionInfo, operations type) {
		// Storing files to MD-SAL
		// TODO Check if storing the data is correct.
		NodeIdentifier encoding = NodeIdentifier.create(QName.create(Encodings.QNAME, "encoding"));
		NodeIdentifier filterType1 = NodeIdentifier.create(QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "filter-type-1"));
		NodeIdentifier filter1 = NodeIdentifier.create(QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "filter-1"));
		NodeIdentifier stream = NodeIdentifier.create(QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "stream"));
		NodeIdentifier startTime = NodeIdentifier.create(QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "startTime"));
		NodeIdentifier stopTime = NodeIdentifier.create(QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "stopTime"));
		NodeIdentifier subStartTime = NodeIdentifier.create(QName.create(YP_NS, YP_NS_DATE, "subscription-start-time"));
		NodeIdentifier subStopTime = NodeIdentifier.create(QName.create(YP_NS, YP_NS_DATE, "subscription-stop-time"));
		// NodeIdentifier dscp = NodeIdentifier.create(QName.create(YP_NS,
		// YP_NS_DATE, "dscp"));

		NodeIdentifier filtertype1 = new NodeIdentifier(FilterType1.QNAME);
		// NodeIdentifier subDependency = new
		// NodeIdentifier(Y_SUB_DEPENDENCY_NAME);
		// NodeIdentifier subPriority = new NodeIdentifier(Y_SUB_PRIORITY_NAME);
		NodeIdentifier updateTrigger = new NodeIdentifier(Y_UPDATE_TRIGGER_NAME);
		NodeIdentifier period = new NodeIdentifier(Y_PERIOD_NAME);
		NodeIdentifier dampeningPeriod = new NodeIdentifier(Y_DAMPENING_PERIOD_NAME);
		NodeIdentifier noSynchOnStart = new NodeIdentifier(Y_NO_SYNCH_ON_START_NAME);
		NodeIdentifier exlcludedChange = new NodeIdentifier(Y_EXCLUDED_CHANGE_NAME);
		NodeIdentifier filtertype = new NodeIdentifier(N_SUBTREE_FILTER_TYPE_NAME);
		NodeIdentifier subtreeFilter = new NodeIdentifier(Y_SUBTREE_FILTER_NAME);
		NodeIdentifier updateFilter = new NodeIdentifier(Y_UPDATE_FILTER_NAME);
		NodeIdentifier filter = new NodeIdentifier(N_FILTER_NAME);
		NodeIdentifier filt = new NodeIdentifier(N_FILT_NAME);

		// Part where Siegert should add the 'call_home' parameter QNAME

		if (type.equals(operations.delete)) {
			subscriptionInfo = this.getSubscription(subscriptionInfo.getSubscriptionId());
		}
		Long sidValue = Long.valueOf(subscriptionInfo.getSubscriptionId());
		// Short subPriorityValue =
		// Short.valueOf(subscriptionInfo.getSubscriptionPriority());
		// Short dscpValue = Short.valueOf(subscriptionInfo.getDscp());
		// ChoiceNode c1 = Builders.choiceBuilder().withNodeIdentifier(result)
		// .withChild(ImmutableNodes.leafNode(subid, sidValue)).build();
		ChoiceNode c2 = null;
		// Whether its periodic or on-Change the node must be built differently
		if (!(subscriptionInfo.getPeriod() == null)) {
			LOG.info("Period" + subscriptionInfo.getPeriod().toString());
			c2 = Builders.choiceBuilder().withNodeIdentifier(updateTrigger)
					.withChild(ImmutableNodes.leafNode(period, subscriptionInfo.getPeriod())).build();
		} else {
			LOG.info("DP" + subscriptionInfo.getDampeningPeriod().toString());
			if (subscriptionInfo.getNoSynchOnStart()) {
				c2 = Builders.choiceBuilder().withNodeIdentifier(updateTrigger)
						.withChild(ImmutableNodes.leafNode(dampeningPeriod, subscriptionInfo.getDampeningPeriod()))
						.withChild(ImmutableNodes.leafNode(noSynchOnStart, null))
						// .withChild(ImmutableNodes.leafNode(exlcludedChange,
						// subscriptionInfo.getExcludedChange()))
						.build();
			} else {
				c2 = Builders.choiceBuilder().withNodeIdentifier(updateTrigger)
						.withChild(ImmutableNodes.leafNode(dampeningPeriod, subscriptionInfo.getDampeningPeriod()))
						// .withChild(ImmutableNodes.leafNode(exlcludedChange,
						// subscriptionInfo.getExcludedChange()))
						.build();
			}
		}
		YangInstanceIdentifier pid = YangInstanceIdentifier.builder().node(Subscriptions.QNAME).node(Subscription.QNAME)
				.build();

		NodeIdentifierWithPredicates p = new NodeIdentifierWithPredicates(
				QName.create(Subscriptions.QNAME, "subscription"), QName.create(Subscriptions.QNAME, "subscription-id"),
				sidValue);

		ChoiceNode c3 = null;
		MapEntryNode men = null;
		if (!(subscriptionInfo.getFilter() == null)) {
			// AnyXmlNode test =
			// Builders.anyXmlBuilder().withNodeIdentifier(filter1)
			// .withValue(subscriptionInfo.getFilter()).build();
			// ChoiceNode c4 =
			// Builders.choiceBuilder().withNodeIdentifier(updateFilter)
			// .withChild(ImmutableNodes.leafNode(filter,
			// subscriptionInfo.getFilter().toString())).build();
			c3 = Builders.choiceBuilder().withNodeIdentifier(filtertype1)
					.withChild(ImmutableNodes.leafNode(filter1, XmlUtil.toString((Element) subscriptionInfo.getFilter().getNode()))).build();
			// DataNodeContainer c5 = (DataNodeContainer)
			// Builders.leafBuilder().withNodeIdentifier(filter).build();
			// AnyXmlNodeDataWithSchema c6 = (AnyXmlNodeDataWithSchema)
			// Builders.leafBuilder().withNodeIdentifier(filter).build();
			// LOG.warn("c5: "+c5);
			// LOG.warn("c6: "+c6);
			men = ImmutableNodes.mapEntryBuilder().withNodeIdentifier(p).withChild(c2)
					// Part where Siegert should add the 'call_home' parameter
					// NODE
					// .withChild(c5)

					.withChild(ImmutableNodes.leafNode(stream, subscriptionInfo.getStream()))
					.withChild(ImmutableNodes.leafNode(subStartTime, subscriptionInfo.getSubscriptionStartTime()))
					.withChild(ImmutableNodes.leafNode(subStopTime, subscriptionInfo.getSubscriptionStopTime()))
					// .withChild(ImmutableNodes.leafNode(subPriority,
					// subPriorityValue))
					// .withChild(ImmutableNodes.leafNode(subDependency,
					// subscriptionInfo.getSubscriptionDependency()))
					// .withChild(ImmutableNodes.leafNode(dscp, dscpValue))
					.withChild(ImmutableNodes.leafNode(startTime, subscriptionInfo.getStartTime()))
					.withChild(ImmutableNodes.leafNode(stopTime, subscriptionInfo.getStopTime()))
					.withChild(ImmutableNodes.leafNode(encoding, subscriptionInfo.getEncoding())).withChild(c3).build();
		} else {
			men = ImmutableNodes.mapEntryBuilder().withNodeIdentifier(p)
					// Part where Siegert should add the 'call_home' parameter
					// NODE
					// .withChild(ImmutableNodes.leafNode(updateTrigger,
					// subscriptionInfo.getUpdateTrigger()))
					.withChild(c2).withChild(ImmutableNodes.leafNode(stream, subscriptionInfo.getStream()))
					.withChild(ImmutableNodes.leafNode(subStartTime, subscriptionInfo.getSubscriptionStartTime()))
					.withChild(ImmutableNodes.leafNode(subStopTime, subscriptionInfo.getSubscriptionStopTime()))
					// .withChild(ImmutableNodes.leafNode(subPriority,
					// subPriorityValue))
					// .withChild(ImmutableNodes.leafNode(subDependency,
					// subscriptionInfo.getSubscriptionDependency()))
					// .withChild(ImmutableNodes.leafNode(dscp, dscpValue))
					.withChild(ImmutableNodes.leafNode(startTime, subscriptionInfo.getStartTime()))
					.withChild(ImmutableNodes.leafNode(stopTime, subscriptionInfo.getStopTime()))
					.withChild(ImmutableNodes.leafNode(encoding, subscriptionInfo.getEncoding())).build();
		}
		LOG.info("men: " + men);

		DOMDataWriteTransaction tx = this.globalDomDataBroker.newWriteOnlyTransaction();
		YangInstanceIdentifier yid = pid
				.node(new NodeIdentifierWithPredicates(Subscription.QNAME, men.getIdentifier().getKeyValues()));

		// Distinguish whether if a subscription has to be established,
		// deleted or modified in MD-SAL data store.
		switch (type) {
		case establish:
			if (!checkIfSubscriptionExists(subscriptionInfo.getSubscriptionId())) {
				tx.merge(LogicalDatastoreType.OPERATIONAL, yid, men);
				// Storing files locally
				masterSubMap.put(subscriptionInfo.getSubscriptionId(), subscriptionInfo);
				LOG.info("Subscription stored...");
			} else {
				LOG.info("Subscription already exists");
			}
			break;
		case delete:
			if (checkIfSubscriptionExists(subscriptionInfo.getSubscriptionId())) {
				tx.delete(LogicalDatastoreType.OPERATIONAL, yid);
				masterSubMap.remove(subscriptionInfo.getSubscriptionId(), subscriptionInfo);
				LOG.info("Subscription has been deleted");
			} else {
				LOG.info("Subscription didn't exist");
			}
			break;
		case modify:
			if (checkIfSubscriptionExists(subscriptionInfo.getSubscriptionId())) {
				tx.put(LogicalDatastoreType.OPERATIONAL, yid, men);
				masterSubMap.put(subscriptionInfo.getSubscriptionId(), subscriptionInfo);
				LOG.info("Subscription modified...");
			} else {
				LOG.info("Subscription didn't exist");
			}
			break;
		default:
			break;
		}
		try {
			tx.submit().checkedGet();
			LOG.info("Transaction has been submitted");
		} catch (TransactionCommitFailedException e) {
			e.printStackTrace();
			LOG.info("Uuups");
		}
		LOG.info("MD-SAL has been updated");
	}

	/**
	 * Checks if the subscription aleady exists.
	 * 
	 * @param subscriptionID
	 *            The individual identifier, which is needed to find the
	 *            selected subscription inside the local map.
	 */
	public Boolean checkIfSubscriptionExists(String sub_id) {
		if (masterSubMap.get(sub_id) == null) {
			LOG.info("Subscription not existing");
			return false;
		}
		LOG.info("Subscription existing");
		return true;
	}

	/**
	 * Creating protected constructor for creating singleton instance
	 * 
	 * @param subscriptionID
	 *            The individual identifier, which is needed to find the
	 *            selected subscription inside the local map.
	 */
	public SubscriptionInfo getSubscription(String subscriptionID) {
		return this.masterSubMap.get(subscriptionID);
	}
}
