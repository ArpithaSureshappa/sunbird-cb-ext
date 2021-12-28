package org.sunbird.progress.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.sunbird.cassandra.utils.CassandraOperation;
import org.sunbird.common.service.OutboundRequestHandlerServiceImpl;
import org.sunbird.common.util.CbExtServerProperties;
import org.sunbird.common.util.Constants;
import org.sunbird.core.logger.CbExtLogger;
import org.sunbird.progress.model.MandatoryContentInfo;
import org.sunbird.progress.model.MandatoryContentResponse;
import org.sunbird.progress.model.UserProgressRequest;
import org.sunbird.progress.model.BatchEnrolment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class MandatoryContentServiceImpl implements MandatoryContentService {

	@Autowired
	private OutboundRequestHandlerServiceImpl outboundReqService;

	@Autowired
	private CbExtServerProperties cbExtServerProperties;

	@Autowired
	private CassandraOperation cassandraOperation;

	private CbExtLogger logger = new CbExtLogger(getClass().getName());

	private ObjectMapper mapper = new ObjectMapper();

	@Override
	public MandatoryContentResponse getMandatoryContentStatusForUser(String authUserToken, String rootOrg, String org,
			String userId) {
		MandatoryContentResponse response = new MandatoryContentResponse();

		Map<String, Object> propertyMap = new HashMap<>();
		propertyMap.put(Constants.ROOT_ORG, rootOrg);
		propertyMap.put(Constants.ORG, org);
		List<Map<String, Object>> contentList = cassandraOperation.getRecordsByProperties(Constants.DATABASE,
				Constants.MANDATORY_CONTENT, propertyMap, new ArrayList<>());

		if (CollectionUtils.isEmpty(contentList)) {
			logger.info("getMandatoryContentStatusForUser: There are no mandatory Content set in DB.");
			return response;
		}

		for (Map<String, Object> content : contentList) {
			String contentId = (String) content.get(Constants.CONTENT_ID);
			content.remove(Constants.CONTENT_ID);
			MandatoryContentInfo info = mapper.convertValue(content, MandatoryContentInfo.class);
			response.addContentInfo(contentId, info);
		}

		try {
			logger.info("getMandatoryContentStatusForUser: MandatoryCourse Details : "
					+ new ObjectMapper().writer().withDefaultPrettyPrinter().writeValueAsString(response));
		} catch (JsonProcessingException e) {
			logger.error(e);
		}
		enrichProgressDetails(authUserToken, response, userId);
		try {
			logger.info("getMandatoryContentStatusForUser: Ret Value is: "
					+ new ObjectMapper().writer().withDefaultPrettyPrinter().writeValueAsString(response));
		} catch (JsonProcessingException e) {
			logger.error(e);
		}
		Iterator<MandatoryContentInfo> entries = response.getContentDetails().values().iterator();
		boolean isCompleted = false;
		while (entries.hasNext()) {
			MandatoryContentInfo entry = entries.next();
			if (entry.getUserProgress() < entry.getMinProgressForCompletion()) {
				response.setMandatoryCourseCompleted(false);
				isCompleted = false;
				break;
			} else {
				isCompleted = true;
			}
		}
		if (isCompleted) {
			response.setMandatoryCourseCompleted(true);
		}
		return response;
	}

	public void enrichProgressDetails(String authUserToken, MandatoryContentResponse mandatoryContentInfo,
			String userId) {
		HashMap<String, Object> req;
		HashMap<String, Object> reqObj;
		List<String> fields = Arrays.asList("progressdetails");
		HashMap<String, String> headersValues = new HashMap<>();
		headersValues.put("X-Authenticated-User-Token", authUserToken);
		headersValues.put("Authorization", cbExtServerProperties.getSbApiKey());
		for (Map.Entry<String, MandatoryContentInfo> infoMap : mandatoryContentInfo.getContentDetails().entrySet()) {
			try {
				req = new HashMap<>();
				reqObj = new HashMap<>();
				reqObj.put("userId", userId);
				reqObj.put("courseId", infoMap.getKey());
				reqObj.put("batchId", infoMap.getValue().getBatchId());
				reqObj.put("fields", fields);
				req.put("request", reqObj);
				Map<String, Object> response = outboundReqService.fetchResultUsingPost(
						cbExtServerProperties.getCourseServiceHost() + cbExtServerProperties.getProgressReadEndPoint(),
						req, headersValues);
				if (response.get("responseCode").equals("OK")) {
					List<Object> result = (List<Object>) ((HashMap<String, Object>) response.get("result"))
							.get("contentList");
					if (!CollectionUtils.isEmpty(result)) {
						Optional<Object> optionResult = result.stream().findFirst();
						if (optionResult.isPresent()) {
							Map<String, Object> content = (Map<String, Object>) optionResult.get();
							BigDecimal progress = new BigDecimal(content.get("completionPercentage").toString());
							mandatoryContentInfo.getContentDetails().get(infoMap.getKey())
									.setUserProgress(progress.floatValue());
						}
					}
				}
			} catch (Exception ex) {
				logger.error(ex);
			}
		}
	}

	public Map<String, Object> getUserProgress(Map<String, Object> requestBody) {
		Map<String, Object> result = new HashMap<>();
		try {
			UserProgressRequest requestData = validateGetBatchEnrolment(requestBody);
			if (ObjectUtils.isEmpty(requestData) || ObjectUtils.isEmpty(requestData.getBatchList())) {
				result.put(Constants.STATUS, Constants.FAILED);
				result.put(Constants.MESSAGE, "check your request params");
				return result;
			}

			// get all enrolled details
			List<Map<String, Object>> userEnrolmentList = new ArrayList<>();
			for (BatchEnrolment request : requestData.getBatchList()) {
				if (StringUtils.isNotBlank(request.getBatchId())) {
					Map<String, Object> propertyMap = new HashMap<>();
					propertyMap.put(Constants.BATCH_ID, request.getBatchId());
					propertyMap.put(Constants.ACTIVE, Boolean.TRUE);
					if (request.getUserList() != null && !request.getUserList().isEmpty()) {
						propertyMap.put(Constants.USER_ID_CONSTANT, request.getUserList());
					}
					userEnrolmentList.addAll(cassandraOperation.getRecordsByProperties(Constants.COURSE_DB,
							Constants.USER_ENROLMENT, propertyMap,
							new ArrayList<>(Arrays.asList(Constants.USER_ID_CONSTANT, Constants.COURSE_ID,
									Constants.BATCH_ID, "completionpercentage", "progress", Constants.STATUS,
									"issued_certificates"))));
				}
			}

			if (userEnrolmentList.size() > 100) {
				userEnrolmentList = userEnrolmentList.subList(0, 100);
			}

			List<String> enrolledUserId = userEnrolmentList.stream()
					.map(obj -> (String) obj.get(Constants.USER_ID_CONSTANT)).collect(Collectors.toList());
			// get all user details
			List<Map<String, Object>> userList = cassandraOperation.getRecordsByProperties(Constants.DATABASE,
					Constants.USER, new HashMap<String, Object>() {
						{
							put(Constants.ID, enrolledUserId);
						}
					}, new ArrayList<>(Arrays.asList(Constants.ID, "firstname", "lastname", Constants.EMAIL)));

			Map<String, Map<String, Object>> userMap = userList.stream()
					.collect(Collectors.toMap(obj -> (String) obj.get(Constants.ID), obj -> obj));

			// append user details with enrollment data
			for (Map<String, Object> userEnrolment : userEnrolmentList) {
				if (userMap.containsKey(userEnrolment.get(Constants.USER_ID_CONSTANT))) {
					userEnrolment.putAll(userMap.get(userEnrolment.get(Constants.USER_ID_CONSTANT)));
				}
			}

			result.put(Constants.STATUS, Constants.SUCCESSFUL);
			result.put(Constants.RESULT, userEnrolmentList);
		} catch (Exception ex) {
			result.put(Constants.STATUS, Constants.FAILED);
			logger.error(ex);
		}
		return result;
	}

	private UserProgressRequest validateGetBatchEnrolment(Map<String, Object> requestBody) {
		try {
			if (requestBody.containsKey(Constants.REQUEST)) {
				return mapper.convertValue(requestBody.get(Constants.REQUEST), UserProgressRequest.class);
			}
		} catch (Exception e) {
			logger.error(e);
		}
		return null;
	}
}
