/*
 *       Copyright© (2018-2019) WeBank Co., Ltd.
 *
 *       This file is part of weid-java-sdk.
 *
 *       weid-java-sdk is free software: you can redistribute it and/or modify
 *       it under the terms of the GNU Lesser General Public License as published by
 *       the Free Software Foundation, either version 3 of the License, or
 *       (at your option) any later version.
 *
 *       weid-java-sdk is distributed in the hope that it will be useful,
 *       but WITHOUT ANY WARRANTY; without even the implied warranty of
 *       MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *       GNU Lesser General Public License for more details.
 *
 *       You should have received a copy of the GNU Lesser General Public License
 *       along with weid-java-sdk.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.webank.weid.service.impl.engine.fiscov1;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.StringUtils;
import org.bcos.web3j.abi.datatypes.Address;
import org.bcos.web3j.abi.datatypes.DynamicArray;
import org.bcos.web3j.abi.datatypes.Type;
import org.bcos.web3j.abi.datatypes.generated.Bytes32;
import org.bcos.web3j.abi.datatypes.generated.Uint8;
import org.bcos.web3j.crypto.ECKeyPair;
import org.bcos.web3j.crypto.Keys;
import org.bcos.web3j.crypto.Sign;
import org.bcos.web3j.crypto.Sign.SignatureData;
import org.bcos.web3j.protocol.core.methods.response.TransactionReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webank.weid.constant.ErrorCode;
import com.webank.weid.constant.WeIdConstant;
import com.webank.weid.contract.v1.Evidence;
import com.webank.weid.contract.v1.Evidence.AddHashLogEventResponse;
import com.webank.weid.contract.v1.Evidence.AddSignatureLogEventResponse;
import com.webank.weid.contract.v1.EvidenceFactory;
import com.webank.weid.contract.v1.EvidenceFactory.CreateEvidenceLogEventResponse;
import com.webank.weid.protocol.base.EvidenceInfo;
import com.webank.weid.protocol.base.EvidenceSignInfo;
import com.webank.weid.protocol.response.ResponseData;
import com.webank.weid.protocol.response.TransactionInfo;
import com.webank.weid.service.impl.engine.BaseEngine;
import com.webank.weid.service.impl.engine.EvidenceServiceEngine;
import com.webank.weid.util.DataToolUtils;
import com.webank.weid.util.WeIdUtils;

/**
 * EvidenceServiceEngineV1 calls the evidence contract which runs on FISCO BCOS 1.3.x version.
 *
 * @author yanggang, chaoxinhu
 */
public class EvidenceServiceEngineV1 extends BaseEngine implements EvidenceServiceEngine {

    private static final Logger logger = LoggerFactory.getLogger(EvidenceServiceEngineV1.class);

    /**
     * Create evidence in 1.x FISCO-BCOS.
     *
     * @param sigData signature Data
     * @param hashAttributes hash value
     * @param extraValueList extra value
     * @param privateKey private key
     * @param signerList declared signers
     * @return evidence address
     */
    @Override
    public ResponseData<String> createEvidence(
        Sign.SignatureData sigData,
        List<String> hashAttributes,
        List<String> extraValueList,
        String privateKey,
        List<String> signerList
    ) {

        try {
            Bytes32 r = DataToolUtils.bytesArrayToBytes32(sigData.getR());
            Bytes32 s = DataToolUtils.bytesArrayToBytes32(sigData.getS());
            Uint8 v = DataToolUtils.intToUnt8(Integer.valueOf(sigData.getV()));
            List<Address> signer = new ArrayList<>();
            if (signerList == null || signerList.size() == 0) {
                // Evidence has only one signer - default to be the WeID behind the private key
                ECKeyPair keyPair = ECKeyPair.create(new BigInteger(privateKey));
                signer.add(new Address(Keys.getAddress(keyPair)));
            } else {
                // Evidence has a pre-defined signer list
                for (String signerWeId : signerList) {
                    signer.add(new Address(WeIdUtils.convertWeIdToAddress(signerWeId)));
                }
            }
            EvidenceFactory evidenceFactory =
                reloadContract(
                    fiscoConfig.getEvidenceAddress(),
                    privateKey,
                    EvidenceFactory.class
                );
            Future<TransactionReceipt> future = evidenceFactory.createEvidence(
                new DynamicArray<Bytes32>(generateBytes32List(hashAttributes)),
                new DynamicArray<Address>(signer),
                r,
                s,
                v,
                new DynamicArray<Bytes32>(generateBytes32List(extraValueList))
            );

            TransactionReceipt receipt = future.get(
                WeIdConstant.TRANSACTION_RECEIPT_TIMEOUT,
                TimeUnit.SECONDS);
            TransactionInfo info = new TransactionInfo(receipt);
            List<CreateEvidenceLogEventResponse> eventResponseList =
                EvidenceFactory.getCreateEvidenceLogEvents(receipt);
            CreateEvidenceLogEventResponse event = eventResponseList.get(0);

            if (event != null) {
                ErrorCode innerResponse =
                    verifyCreateEvidenceEvent(
                        event.retCode.getValue().intValue(),
                        event.addr.toString()
                    );
                if (ErrorCode.SUCCESS.getCode() != innerResponse.getCode()) {
                    return new ResponseData<>(StringUtils.EMPTY, innerResponse, info);
                }
                return new ResponseData<>(event.addr.toString(), ErrorCode.SUCCESS, info);
            } else {
                logger.error(
                    "create evidence failed due to transcation event decoding failure."
                );
                return new ResponseData<>(StringUtils.EMPTY,
                    ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR, info);
            }
        } catch (TimeoutException e) {
            logger.error("create evidence failed due to system timeout. ", e);
            return new ResponseData<>(StringUtils.EMPTY, ErrorCode.TRANSACTION_TIMEOUT);
        } catch (InterruptedException | ExecutionException e) {
            logger.error("create evidence failed due to transaction error. ", e);
            return new ResponseData<>(StringUtils.EMPTY, ErrorCode.TRANSACTION_EXECUTE_ERROR);
        }
    }

    /**
     * Add signature to an evidence.
     *
     * @param sigData signature data
     * @param privateKey private key
     * @return true if succeeded, false otherwise
     */
    @Override
    public ResponseData<Boolean> addSignature(Sign.SignatureData sigData, String privateKey,
        String evidenceAddress) {
        Evidence evidence =
            reloadContract(
                evidenceAddress,
                privateKey,
                Evidence.class
            );
        Bytes32 r = DataToolUtils.bytesArrayToBytes32(sigData.getR());
        Bytes32 s = DataToolUtils.bytesArrayToBytes32(sigData.getS());
        Uint8 v = DataToolUtils.intToUnt8(Integer.valueOf(sigData.getV()));
        Future<TransactionReceipt> future = evidence.addSignature(r, s, v);
        try {
            TransactionReceipt receipt = future.get(
                WeIdConstant.TRANSACTION_RECEIPT_TIMEOUT,
                TimeUnit.SECONDS);
            TransactionInfo info = new TransactionInfo(receipt);
            List<AddSignatureLogEventResponse> eventResponseList =
                Evidence.getAddSignatureLogEvents(receipt);
            AddSignatureLogEventResponse event = eventResponseList.get(0);
            if (event != null) {
                if (event.retCode.getValue().intValue()
                    == ErrorCode.CREDENTIAL_EVIDENCE_CONTRACT_FAILURE_ILLEAGAL_INPUT.getCode()) {
                    return new ResponseData<>(false,
                        ErrorCode.CREDENTIAL_EVIDENCE_CONTRACT_FAILURE_ILLEAGAL_INPUT, info);
                }
                return new ResponseData<>(true, ErrorCode.SUCCESS, info);
            } else {
                logger.error(
                    "add signature failed due to transcation event decoding failure."
                );
                return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR, info);
            }
        } catch (TimeoutException e) {
            logger.error("add signature failed due to system timeout. ", e);
            return new ResponseData<>(false, ErrorCode.TRANSACTION_TIMEOUT);
        } catch (InterruptedException | ExecutionException e) {
            logger.error("add signature failed due to transaction error. ", e);
            return new ResponseData<>(false, ErrorCode.TRANSACTION_EXECUTE_ERROR);
        }
    }

    /**
     * Set hash value an evidence.
     *
     * @param hashAttributes hash value
     * @param privateKey private key
     * @param evidenceAddress evidence address
     * @return true if succeeded, false otherwise
     */
    @Override
    public ResponseData<Boolean> setHashValue(List<String> hashAttributes, String privateKey,
        String evidenceAddress) {
        Evidence evidence =
            reloadContract(
                evidenceAddress,
                privateKey,
                Evidence.class
            );
        Future<TransactionReceipt> future = evidence
            .setHash(new DynamicArray<Bytes32>(generateBytes32List(hashAttributes)));
        try {
            TransactionReceipt receipt = future.get(
                WeIdConstant.TRANSACTION_RECEIPT_TIMEOUT,
                TimeUnit.SECONDS);
            TransactionInfo info = new TransactionInfo(receipt);
            List<AddHashLogEventResponse> eventResponseList = Evidence.getAddHashLogEvents(receipt);
            AddHashLogEventResponse event = eventResponseList.get(0);
            if (event != null) {
                if (event.retCode.getValue().intValue()
                    == ErrorCode.CREDENTIAL_EVIDENCE_CONTRACT_FAILURE_ILLEAGAL_INPUT.getCode()) {
                    return new ResponseData<>(false,
                        ErrorCode.CREDENTIAL_EVIDENCE_CONTRACT_FAILURE_ILLEAGAL_INPUT, info);
                }
                return new ResponseData<>(true, ErrorCode.SUCCESS, info);
            } else {
                logger.error(
                    "set hash value failed due to transaction event decoding failure."
                );
                return new ResponseData<>(false, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR, info);
            }
        } catch (TimeoutException e) {
            logger.error("add signature failed due to system timeout. ", e);
            return new ResponseData<>(false, ErrorCode.TRANSACTION_TIMEOUT);
        } catch (InterruptedException | ExecutionException e) {
            logger.error("add signature failed due to transaction error. ", e);
            return new ResponseData<>(false, ErrorCode.TRANSACTION_EXECUTE_ERROR);
        }
    }

    private List<Bytes32> generateBytes32List(List<String> bytes32List) {
        int desiredLength = bytes32List.size();
        List<Bytes32> finalList = new ArrayList<>();
        for (int i = 0; i < desiredLength; i++) {
            finalList.add(DataToolUtils.stringToBytes32(bytes32List.get(i)));
        }
        return finalList;
    }

    /**
     * Get an evidence info.
     *
     * @param evidenceAddress evidence addr
     * @return evidence info
     */
    @Override
    public ResponseData<EvidenceInfo> getInfo(String evidenceAddress) {
        try {
            Evidence evidence = (Evidence) getContractService(evidenceAddress, Evidence.class);
            List<Type> rawResult =
                evidence.getInfo()
                    .get(WeIdConstant.TRANSACTION_RECEIPT_TIMEOUT, TimeUnit.SECONDS);

            if (rawResult == null) {
                return new ResponseData<>(null, ErrorCode.CREDENTIAL_EVIDENCE_BASE_ERROR);
            }
            List<Bytes32> credentialHashList = ((DynamicArray<Bytes32>) rawResult.get(0))
                .getValue();
            List<Address> issuerList = ((DynamicArray<Address>) rawResult.get(1)).getValue();

            EvidenceInfo evidenceInfoData = new EvidenceInfo();
            if (credentialHashList.size() < 1) {
                evidenceInfoData.setCredentialHash(WeIdConstant.HEX_PREFIX);
            } else {
                evidenceInfoData.setCredentialHash(
                    WeIdConstant.HEX_PREFIX + DataToolUtils
                        .bytes32ToString(credentialHashList.get(0))
                        + DataToolUtils.bytes32ToString(credentialHashList.get(1)));
            }

            List<String> signerStringList = new ArrayList<>();
            for (Address addr : issuerList) {
                signerStringList.add(WeIdUtils.convertAddressToWeId(addr.toString()));
            }

            List<String> signaturesList = new ArrayList<>();
            List<Bytes32> rlist = ((DynamicArray<Bytes32>) rawResult.get(2)).getValue();
            List<Bytes32> slist = ((DynamicArray<Bytes32>) rawResult.get(3)).getValue();
            List<Uint8> vlist = ((DynamicArray<Uint8>) rawResult.get(4)).getValue();
            byte v;
            byte[] r;
            byte[] s;
            for (int index = 0; index < rlist.size(); index++) {
                v = (byte) (vlist.get(index).getValue().intValue());
                r = rlist.get(index).getValue();
                s = slist.get(index).getValue();
                if ((int) v == 0) {
                    // skip empty signatures
                    continue;
                }
                SignatureData sigData = new SignatureData(v, r, s);
                signaturesList.add(
                    new String(
                        DataToolUtils.base64Encode(
                            DataToolUtils.simpleSignatureSerialization(sigData)
                        ),
                        StandardCharsets.UTF_8
                    )
                );
            }

            for (int index = 0; index < signerStringList.size(); index++) {
                EvidenceSignInfo signInfo = new EvidenceSignInfo();
                signInfo.setSignature(signaturesList.get(index));
                // TODO add timestamp here
                evidenceInfoData.getSignInfo().put(signerStringList.get(index), signInfo);
            }
            return new ResponseData<>(evidenceInfoData, ErrorCode.SUCCESS);
        } catch (TimeoutException e) {
            logger.error("create evidence failed due to system timeout. ", e);
            return new ResponseData<>(null, ErrorCode.TRANSACTION_TIMEOUT);
        } catch (InterruptedException | ExecutionException e) {
            logger.error("create evidence failed due to transaction error. ", e);
            return new ResponseData<>(null, ErrorCode.TRANSACTION_EXECUTE_ERROR);
        }
    }
}
