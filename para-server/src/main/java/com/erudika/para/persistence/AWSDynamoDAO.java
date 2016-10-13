/*
 * Copyright 2013-2016 Erudika. http://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.persistence;

import com.erudika.para.annotations.Locked;
import com.amazonaws.services.dynamodbv2.model.AttributeAction;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemResult;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.KeysAndAttributes;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.Index;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Page;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.AttributeValueUpdate;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemResult;
import com.amazonaws.services.dynamodbv2.model.DeleteItemRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutRequest;
import com.amazonaws.services.dynamodbv2.model.ReturnConsumedCapacity;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;
import com.amazonaws.services.dynamodbv2.model.WriteRequest;
import com.erudika.para.core.ParaObject;
import com.erudika.para.core.utils.ParaObjectUtils;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.Pager;
import com.erudika.para.utils.Utils;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.erudika.para.persistence.AWSDynamoUtils.*;
import java.util.Set;
import java.util.TreeSet;

/**
 * An implementation of the {@link DAO} interface using AWS DynamoDB as a data store.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
@Singleton
public class AWSDynamoDAO implements DAO {

	private static final Logger logger = LoggerFactory.getLogger(AWSDynamoDAO.class);
	private static final int MAX_ITEMS_PER_WRITE = 10; // Amazon DynamoDB limit ~= WRITE CAP
	private static final int MAX_KEYS_PER_READ = 100; // Amazon DynamoDB limit = 100

	/**
	 * No-args constructor.
	 */
	public AWSDynamoDAO() { }

	AmazonDynamoDBClient client() {
		return AWSDynamoUtils.getClient();
	}

	/////////////////////////////////////////////
	//			CORE FUNCTIONS
	/////////////////////////////////////////////

	@Override
	public <P extends ParaObject> String create(String appid, P so) {
		if (so == null) {
			return null;
		}
		if (StringUtils.isBlank(so.getId())) {
			so.setId(Utils.getNewId());
		}
		if (so.getTimestamp() == null) {
			so.setTimestamp(Utils.timestamp());
		}
		so.setAppid(appid);
		createRow(so.getId(), appid, toRow(so, null));
		logger.debug("DAO.create() {}->{}", appid, so.getId());
		return so.getId();
	}

	@Override
	public <P extends ParaObject> P read(String appid, String key) {
		if (StringUtils.isBlank(key)) {
			return null;
		}
		P so = fromRow(readRow(key, appid));
		logger.debug("DAO.read() {}->{}", appid, key);
		return so != null ? so : null;
	}

	@Override
	public <P extends ParaObject> void update(String appid, P so) {
		if (so != null && so.getId() != null) {
			so.setUpdated(Utils.timestamp());
			updateRow(so.getId(), appid, toRow(so, Locked.class));
			logger.debug("DAO.update() {}->{}", appid, so.getId());
		}
	}

	@Override
	public <P extends ParaObject> void delete(String appid, P so) {
		if (so != null && so.getId() != null) {
			deleteRow(so.getId(), appid);
			logger.debug("DAO.delete() {}->{}", appid, so.getId());
		}
	}

	/////////////////////////////////////////////
	//				ROW FUNCTIONS
	/////////////////////////////////////////////

	private String createRow(String key, String appid, Map<String, AttributeValue> row) {
		if (StringUtils.isBlank(key) || StringUtils.isBlank(appid) || row == null || row.isEmpty()) {
			return null;
		}
		try {
			key = getKeyForAppid(key, appid);
			setRowKey(key, row);
			PutItemRequest putItemRequest = new PutItemRequest(getTableNameForAppid(appid), row);
			client().putItem(putItemRequest);
		} catch (Exception e) {
			logger.error(null, e);
		}
		return key;
	}

	private void updateRow(String key, String appid, Map<String, AttributeValue> row) {
		if (StringUtils.isBlank(key) || StringUtils.isBlank(appid) || row == null || row.isEmpty()) {
			return;
		}
		Map<String, AttributeValueUpdate> rou = new HashMap<String, AttributeValueUpdate>();
		try {
			for (Entry<String, AttributeValue> attr : row.entrySet()) {
				rou.put(attr.getKey(), new AttributeValueUpdate(attr.getValue(), AttributeAction.PUT));
			}
			UpdateItemRequest updateItemRequest = new UpdateItemRequest(getTableNameForAppid(appid),
					Collections.singletonMap(Config._KEY, new AttributeValue(getKeyForAppid(key, appid))), rou);
			client().updateItem(updateItemRequest);
		} catch (Exception e) {
			logger.error(null, e);
		}
	}

	private Map<String, AttributeValue> readRow(String key, String appid) {
		if (StringUtils.isBlank(key) || StringUtils.isBlank(appid)) {
			return null;
		}
		Map<String, AttributeValue> row = null;
		try {
			GetItemRequest getItemRequest = new GetItemRequest(getTableNameForAppid(appid),
					Collections.singletonMap(Config._KEY, new AttributeValue(getKeyForAppid(key, appid))));
			GetItemResult res = client().getItem(getItemRequest);
			if (res != null && res.getItem() != null && !res.getItem().isEmpty()) {
				row = res.getItem();
			}
		} catch (Exception e) {
			logger.error(null, e);
		}
		return (row == null || row.isEmpty()) ? null : row;
	}

	private void deleteRow(String key, String appid) {
		if (StringUtils.isBlank(key) || StringUtils.isBlank(appid)) {
			return;
		}
		try {
			DeleteItemRequest delItemRequest = new DeleteItemRequest(getTableNameForAppid(appid),
					Collections.singletonMap(Config._KEY, new AttributeValue(getKeyForAppid(key, appid))));
			client().deleteItem(delItemRequest);
		} catch (Exception e) {
			logger.error(null, e);
		}
	}

	/////////////////////////////////////////////
	//				BATCH FUNCTIONS
	/////////////////////////////////////////////

	@Override
	public <P extends ParaObject> void createAll(String appid, List<P> objects) {
		writeAll(appid, objects, false);
		logger.debug("DAO.createAll() {}->{}", appid, (objects == null) ? 0 : objects.size());
	}

	@Override
	public <P extends ParaObject> Map<String, P> readAll(String appid, List<String> keys, boolean getAllColumns) {
		if (keys == null || keys.isEmpty() || StringUtils.isBlank(appid)) {
			return new LinkedHashMap<String, P>();
		}

		// DynamoDB doesn't allow duplicate keys in batch requests
		Set<String> keySet = new TreeSet<String>(keys);
		if (keySet.size() < keys.size() && !keySet.isEmpty()) {
			logger.debug("Duplicate keys found - readAll({})", keys);
		}

		Map<String, P> results = new LinkedHashMap<String, P>(keySet.size(), 0.75f, true);
		ArrayList<Map<String, AttributeValue>> keyz = new ArrayList<Map<String, AttributeValue>>(MAX_KEYS_PER_READ);

		try {
			int batchSteps = 1;
			if ((keySet.size() > MAX_KEYS_PER_READ)) {
				batchSteps = (keySet.size() / MAX_KEYS_PER_READ)
						+ ((keySet.size() % MAX_KEYS_PER_READ > 0) ? 1 : 0);
			}

			Iterator<String> it = keySet.iterator();
			int j = 0;

			for (int i = 0; i < batchSteps; i++) {
				while (it.hasNext() && j < MAX_KEYS_PER_READ) {
					String key = it.next();
					results.put(key, null);
					keyz.add(Collections.singletonMap(Config._KEY, new AttributeValue(getKeyForAppid(key, appid))));
					j++;
				}

				KeysAndAttributes kna = new KeysAndAttributes().withKeys(keyz);
				if (!getAllColumns) {
					kna.setAttributesToGet(Arrays.asList(Config._KEY, Config._TYPE));
				}

				batchGet(Collections.singletonMap(getTableNameForAppid(appid), kna), results);
				keyz.clear();
				j = 0;
			}
			logger.debug("DAO.readAll({}) {}", keySet, results.size());
		} catch (Exception e) {
			logger.error("Failed to readAll({}), {}", keys, e);
		}
		return results;
	}

	@Override
	public <P extends ParaObject> List<P> readPage(String appid, Pager pager) {
		LinkedList<P> results = new LinkedList<P>();
		if (StringUtils.isBlank(appid)) {
			return results;
		}
		if (pager == null) {
			pager = new Pager();
		}

		String lastKey = null;

		try {
			if (isSharedAppid(appid)) {
				String lastKeyFragment = "";
				ValueMap valueMap = new ValueMap().withString(":aid", appid);
				NameMap nameMap = null;

				if (!StringUtils.isBlank(pager.getLastKey())) {
					lastKeyFragment = " and #stamp > :ts";
					valueMap.put(":ts", pager.getLastKey());
					nameMap = new NameMap().with("#stamp", Config._TIMESTAMP);
				}

				Index index = getSharedIndex();
				QuerySpec spec = new QuerySpec().
						withMaxPageSize(pager.getLimit()).
						withMaxResultSize(pager.getLimit()).
						withKeyConditionExpression(Config._APPID + " = :aid" + lastKeyFragment).
						withValueMap(valueMap).
						withNameMap(nameMap);

				if (index != null) {
					Page<Item, QueryOutcome> items = index.query(spec).firstPage();
					for (Item item : items) {
						P obj = ParaObjectUtils.setAnnotatedFields(item.asMap());
						if (obj != null) {
							results.add(obj);
						}
					}
					if (!results.isEmpty()) {
						lastKey = Long.toString(results.peekLast().getTimestamp());
					}
				}
			} else {
				ScanRequest scanRequest = new ScanRequest().
						withTableName(getTableNameForAppid(appid)).
						withLimit(pager.getLimit()).
						withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);

				if (!StringUtils.isBlank(pager.getLastKey())) {
					scanRequest = scanRequest.withExclusiveStartKey(Collections.
							singletonMap(Config._KEY, new AttributeValue(pager.getLastKey())));
				}

				ScanResult result = client().scan(scanRequest);

				for (Map<String, AttributeValue> item : result.getItems()) {
					P obj = fromRow(item);
					if (obj != null) {
						results.add(obj);
					}
				}
				if (result.getLastEvaluatedKey() != null) {
					lastKey = result.getLastEvaluatedKey().get(Config._KEY).getS();
				}
			}

			if (lastKey != null) {
				pager.setLastKey(lastKey);
			} else if (!results.isEmpty()) {
				// set last key to be equal to the last result - end reached.
				pager.setLastKey(results.peekLast().getId());
			}
			pager.setCount(pager.getCount() + results.size());
		} catch (Exception e) {
			logger.error(null, e);
		}

		return results;
	}

	@Override
	public <P extends ParaObject> void updateAll(String appid, List<P> objects) {
		// DynamoDB doesn't have a BatchUpdate API yet so we have to do one of the following:
		// 1. update items one by one (chosen for simplicity)
		// 2. call writeAll() - writeAll(appid, objects, true);
		if (objects != null) {
			for (P object : objects) {
				update(appid, object);
			}
		}
		logger.debug("DAO.updateAll() {}", (objects == null) ? 0 : objects.size());
	}

	@Override
	public <P extends ParaObject> void deleteAll(String appid, List<P> objects) {
		if (objects == null || objects.isEmpty() || StringUtils.isBlank(appid)) {
			return;
		}

		List<WriteRequest> reqs = new ArrayList<WriteRequest>(objects.size());
		for (ParaObject object : objects) {
			if (object != null) {
				reqs.add(new WriteRequest().withDeleteRequest(new DeleteRequest().
						withKey(Collections.singletonMap(Config._KEY,
								new AttributeValue(getKeyForAppid(object.getId(), appid))))));
			}
		}
		batchWrite(Collections.singletonMap(getTableNameForAppid(appid), reqs));
		logger.debug("DAO.deleteAll() {}", objects.size());
	}

	private <P extends ParaObject> void batchGet(Map<String, KeysAndAttributes> kna, Map<String, P> results) {
		if (kna == null || kna.isEmpty() || results == null) {
			return;
		}
		try {
			BatchGetItemResult result = client().batchGetItem(new BatchGetItemRequest().
					withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL).withRequestItems(kna));
			if (result == null) {
				return;
			}

			List<Map<String, AttributeValue>> res = result.getResponses().get(kna.keySet().iterator().next());

			for (Map<String, AttributeValue> item : res) {
				P obj = fromRow(item);
				if (obj != null) {
					results.put(obj.getId(), obj);
				}
			}
			logger.debug("batchGet(): total {}, cc {}", res.size(), result.getConsumedCapacity());

			if (result.getUnprocessedKeys() != null && !result.getUnprocessedKeys().isEmpty()) {
				Thread.sleep(1000);
				logger.warn("UNPROCESSED {}", result.getUnprocessedKeys().size());
				batchGet(result.getUnprocessedKeys(), results);
			}
		} catch (Exception e) {
			logger.error(null, e);
		}
	}

	private void batchWrite(Map<String, List<WriteRequest>> items) {
		if (items == null || items.isEmpty()) {
			return;
		}
		try {
			BatchWriteItemResult result = client().batchWriteItem(new BatchWriteItemRequest().
					withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL).withRequestItems(items));
			if (result == null) {
				return;
			}
			logger.debug("batchWrite(): total {}, cc {}", items.size(), result.getConsumedCapacity());

			if (result.getUnprocessedItems() != null && !result.getUnprocessedItems().isEmpty()) {
				Thread.sleep(1000);
				logger.warn("UNPROCESSED {0}", result.getUnprocessedItems().size());
				batchWrite(result.getUnprocessedItems());
			}
		} catch (Exception e) {
			logger.error(null, e);
		}
	}

	private <P extends ParaObject> void writeAll(String appid, List<P> objects, boolean updateOp) {
		if (objects == null || objects.isEmpty() || StringUtils.isBlank(appid)) {
			return;
		}

		List<WriteRequest> reqs = new ArrayList<WriteRequest>(objects.size());
		int batchSteps = 1;
		if ((objects.size() > MAX_ITEMS_PER_WRITE)) {
			batchSteps = (objects.size() / MAX_ITEMS_PER_WRITE) +
					((objects.size() % MAX_ITEMS_PER_WRITE > 0) ? 1 : 0);
		}

		Iterator<P> it = objects.iterator();
		int j = 0;

		for (int i = 0; i < batchSteps; i++) {
			while (it.hasNext() && j < MAX_ITEMS_PER_WRITE) {
				ParaObject object = it.next();
				if (StringUtils.isBlank(object.getId())) {
					object.setId(Utils.getNewId());
				}
				if (object.getTimestamp() == null) {
					object.setTimestamp(Utils.timestamp());
				}
				if (updateOp) {
					object.setUpdated(Utils.timestamp());
				}
				object.setAppid(appid);
				Map<String, AttributeValue> row = toRow(object, null);
				setRowKey(getKeyForAppid(object.getId(), appid), row);
				reqs.add(new WriteRequest().withPutRequest(new PutRequest().withItem(row)));
				j++;
			}
			batchWrite(Collections.singletonMap(getTableNameForAppid(appid), reqs));
			reqs.clear();
			j = 0;
		}
	}

	/////////////////////////////////////////////
	//				MISC FUNCTIONS
	/////////////////////////////////////////////

	private <P extends ParaObject> Map<String, AttributeValue> toRow(P so, Class<? extends Annotation> filter) {
		HashMap<String, AttributeValue> row = new HashMap<String, AttributeValue>();
		if (so == null) {
			return row;
		}
		for (Entry<String, Object> entry : ParaObjectUtils.getAnnotatedFields(so, filter).entrySet()) {
			Object value = entry.getValue();
			if (value != null && !StringUtils.isBlank(value.toString())) {
				row.put(entry.getKey(), new AttributeValue(value.toString()));
			}
		}
		return row;
	}

	private <P extends ParaObject> P fromRow(Map<String, AttributeValue> row) {
		if (row == null || row.isEmpty()) {
			return null;
		}
		Map<String, Object> props = new HashMap<String, Object>();
		for (Entry<String, AttributeValue> col : row.entrySet()) {
			props.put(col.getKey(), col.getValue().getS());
		}
		return ParaObjectUtils.setAnnotatedFields(props);
	}

	private void setRowKey(String key, Map<String, AttributeValue> row) {
		if (row.containsKey(Config._KEY)) {
			logger.warn("Attribute name conflict:  "
				+ "attribute {} will be overwritten! {} is a reserved keyword.", Config._KEY);
		}
		row.put(Config._KEY, new AttributeValue(key));
	}

	//////////////////////////////////////////////////////

	@Override
	public <P extends ParaObject> String create(P so) {
		return create(Config.APP_NAME_NS, so);
	}

	@Override
	public <P extends ParaObject> P read(String key) {
		return read(Config.APP_NAME_NS, key);
	}

	@Override
	public <P extends ParaObject> void update(P so) {
		update(Config.APP_NAME_NS, so);
	}

	@Override
	public <P extends ParaObject> void delete(P so) {
		delete(Config.APP_NAME_NS, so);
	}

	@Override
	public <P extends ParaObject> void createAll(List<P> objects) {
		createAll(Config.APP_NAME_NS, objects);
	}

	@Override
	public <P extends ParaObject> Map<String, P> readAll(List<String> keys, boolean getAllColumns) {
		return readAll(Config.APP_NAME_NS, keys, getAllColumns);
	}

	@Override
	public <P extends ParaObject> List<P> readPage(Pager pager) {
		return readPage(Config.APP_NAME_NS, pager);
	}

	@Override
	public <P extends ParaObject> void updateAll(List<P> objects) {
		updateAll(Config.APP_NAME_NS, objects);
	}

	@Override
	public <P extends ParaObject> void deleteAll(List<P> objects) {
		deleteAll(Config.APP_NAME_NS, objects);
	}

}
