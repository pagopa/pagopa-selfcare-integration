package it.gov.pagopa.apiconfig.selfcareintegration.service;

import it.gov.pagopa.apiconfig.selfcareintegration.exception.AppError;
import it.gov.pagopa.apiconfig.selfcareintegration.exception.AppException;
import it.gov.pagopa.apiconfig.selfcareintegration.model.code.AvailableCodes;
import it.gov.pagopa.apiconfig.selfcareintegration.model.code.CIAssociatedCode;
import it.gov.pagopa.apiconfig.selfcareintegration.model.code.CIAssociatedCodeList;
import it.gov.pagopa.apiconfig.selfcareintegration.model.creditorinstitution.CreditorInstitutionInfo;
import it.gov.pagopa.apiconfig.selfcareintegration.model.station.StationDetails;
import it.gov.pagopa.apiconfig.selfcareintegration.model.station.StationDetailsList;
import it.gov.pagopa.apiconfig.selfcareintegration.repository.ExtendedCreditorInstitutionStationRepository;
import it.gov.pagopa.apiconfig.selfcareintegration.util.Utility;
import it.gov.pagopa.apiconfig.starter.entity.Pa;
import it.gov.pagopa.apiconfig.starter.entity.PaStazionePa;
import it.gov.pagopa.apiconfig.starter.entity.Stazioni;
import it.gov.pagopa.apiconfig.starter.repository.PaRepository;
import it.gov.pagopa.apiconfig.starter.repository.StazioniRepository;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import javax.validation.constraints.NotNull;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * Service containing the method to access creditor institutions related data
 */
@Service
@Transactional
public class CreditorInstitutionsService {

    private static final String UNIONECAMERE_TAX_CODE = "01484460587";
    private static final String ACI_TAX_CODE = "00493410583";
    private static final String ROMA_CAPITALE_TAX_CODE = "02438750586";

    private final Integer applicationCodeMaxValue;

    private final Integer segregationCodeMaxValue;

    private final ExtendedCreditorInstitutionStationRepository ciStationRepository;

    private final StazioniRepository stationRepository;

    private final PaRepository paRepository;

    private final ModelMapper modelMapper;

    @Autowired
    public CreditorInstitutionsService(
            @Value("${sc-int.application_code.max_value}") Integer applicationCodeMaxValue,
            @Value("${sc-int.segregation_code.max_value}") Integer segregationCodeMaxValue,
            ExtendedCreditorInstitutionStationRepository ciStationRepository,
            StazioniRepository stationRepository,
            PaRepository paRepository,
            ModelMapper modelMapper) {
        this.applicationCodeMaxValue = applicationCodeMaxValue;
        this.segregationCodeMaxValue = segregationCodeMaxValue;
        this.ciStationRepository = ciStationRepository;
        this.stationRepository = stationRepository;
        this.paRepository = paRepository;
        this.modelMapper = modelMapper;
    }

    public StationDetailsList getStationsDetailsFromCreditorInstitution(
            @NotNull String creditorInstitutionCode, Pageable pageable) {
        Pa pa = getPaIfExists(creditorInstitutionCode);
        Page<PaStazionePa> queryResult = ciStationRepository.findByFkPa(pa.getObjId(), pageable);
        List<StationDetails> stations =
                queryResult.stream()
                        .map(paStazionePa -> modelMapper.map(paStazionePa.getFkStazione(), StationDetails.class))
                        .toList();
        return StationDetailsList.builder()
                .pageInfo(Utility.buildPageInfo(queryResult))
                .stationsDetailsList(stations)
                .build();
    }

    public CIAssociatedCodeList getApplicationCodesFromCreditorInstitution(
            @NotNull String creditorInstitutionCode, boolean getUsed) {
        Pa pa = getPaIfExists(creditorInstitutionCode);
        List<PaStazionePa> queryResult = ciStationRepository.findByFkPa(pa.getObjId());
        Map<Long, PaStazionePa> alreadyUsedApplicationCodes =
                queryResult.stream()
                        .filter(station -> station.getProgressivo() != null)
                        .collect(Collectors.toMap(PaStazionePa::getProgressivo, station -> station));
        return extractUsedAndUnusedCodes(alreadyUsedApplicationCodes, applicationCodeMaxValue, getUsed);
    }

    public CIAssociatedCodeList getSegregationCodesFromCreditorInstitution(
            @NotNull String creditorInstitutionCode, boolean getUsed, String service) {
        String serviceSubstringToBeSearched = service != null ? service.toLowerCase() : null;
        Pa pa = getPaIfExists(creditorInstitutionCode);
        List<PaStazionePa> queryResult = ciStationRepository.findByFkPa(pa.getObjId());
        Map<Long, PaStazionePa> alreadyUsedApplicationCodes =
                queryResult.stream()
                        .filter(station -> station.getSegregazione() != null)
                        .collect(Collectors.toMap(PaStazionePa::getSegregazione, station -> station));
        // get the set of codes to be obfuscated by service search. If passed service is null, the set
        // is empty and all the element will be returned.
        Set<String> codesToBeObfuscated =
                queryResult.stream()
                        .filter(
                                station -> {
                                    String serviceEndpoint = station.getFkStazione().getServizio();
                                    return serviceSubstringToBeSearched != null
                                            && (serviceEndpoint == null
                                            || !serviceEndpoint.toLowerCase().contains(serviceSubstringToBeSearched));
                                })
                        .map(station -> station.getFkStazione().getIdStazione())
                        .collect(Collectors.toSet());
        // retrieving the data removing the ones to be obfuscated
        CIAssociatedCodeList ciAssociatedCodeList =
                extractUsedAndUnusedCodes(alreadyUsedApplicationCodes, segregationCodeMaxValue, getUsed);
        if (ciAssociatedCodeList.getUsedCodes() != null) {
            ciAssociatedCodeList.setUsedCodes(
                    ciAssociatedCodeList.getUsedCodes().stream()
                            .filter(usedCode -> !codesToBeObfuscated.contains(usedCode.getStationName()))
                            .toList());
        }
        return ciAssociatedCodeList;
    }

    /**
     * Retrieve the list of creditor institution business name given a list of tax code
     *
     * @param taxCodes the list of creditor institution tax codes
     * @return the list of tax code and business name for the requested creditor institutions
     */
    public List<CreditorInstitutionInfo> getCreditorInstitutionInfoList(List<String> taxCodes) {
        Optional<List<Pa>> optionalPaList = this.paRepository.findByIdDominioIn(taxCodes);

        if (optionalPaList.isEmpty()) {
            throw new AppException(AppError.MULTIPLE_CREDITOR_INSTITUTIONS_NOT_FOUND, taxCodes);
        }

        List<Pa> paList = optionalPaList.get();
        if (paList.size() != taxCodes.size()) {
            throw new AppException(AppError.INTERNAL_SERVER_ERROR, "Retrieved less creditor institutions than expected");
        }

        return paList.stream()
                .map(pa -> modelMapper.map(pa, CreditorInstitutionInfo.class))
                .toList();
    }

    /**
     * Retrieve the list of creditor institution's associated to a specific station
     *
     * @param stationCode station's code
     * @return the list of creditor institution's tax codes
     */
    public List<String> getStationCreditorInstitutions(String stationCode) {
        Stazioni station = this.stationRepository.findByIdStazione(stationCode)
                .orElseThrow(() -> new AppException(AppError.STATION_NOT_FOUND, stationCode));

        List<PaStazionePa> stazionePaList = this.ciStationRepository.findByFkStazione(station);
        return stazionePaList.parallelStream()
                .map(paStazionePa -> paStazionePa.getPa().getIdDominio())
                .toList();
    }

    private CIAssociatedCodeList extractUsedAndUnusedCodes(
            Map<Long, PaStazionePa> alreadyUsedCodes, long codeMaxValue, boolean includeUsed) {
        List<CIAssociatedCode> usedCodes = new LinkedList<>();
        List<CIAssociatedCode> unusedCodes = new LinkedList<>();
        // extracting the used and unused code analyzing a sequence of N values and filtering by
        // existing association to station
        LongStream.rangeClosed(0, codeMaxValue)
                .boxed()
                .forEach(
                        codeFromSequence -> {
                            // generate model to be added
                            CIAssociatedCode ciAssociatedCode =
                                    CIAssociatedCode.builder()
                                            .code(
                                                    String.valueOf(
                                                            codeFromSequence < 10
                                                                    ? "0".concat(String.valueOf(codeFromSequence))
                                                                    : codeFromSequence))
                                            .build();
                    // choose the list where must be added the model object
                    if (alreadyUsedCodes.containsKey(codeFromSequence)) {
                                ciAssociatedCode.setStationName(
                                        alreadyUsedCodes.get(codeFromSequence).getFkStazione().getIdStazione());
                                usedCodes.add(ciAssociatedCode);
                    } else {
                                unusedCodes.add(ciAssociatedCode);
                    }
                });

        // generate final object
        return CIAssociatedCodeList.builder()
                .usedCodes(includeUsed ? usedCodes : null)
                .unusedCodes(unusedCodes)
                .build();
    }

    private String getCode(Long codeFromSequence) {
        return codeFromSequence < 10 ? "0".concat(String.valueOf(codeFromSequence)) : String.valueOf(codeFromSequence);
    }

    /**
     * @param creditorInstitutionCode idDominio
     * @return return the PA record from DB if Exists
     * @throws AppException if not found
     */
    protected Pa getPaIfExists(String creditorInstitutionCode) throws AppException {
        Optional<Pa> result = paRepository.findByIdDominio(creditorInstitutionCode);
        if (result.isEmpty()) {
            throw new AppException(AppError.CREDITOR_INSTITUTION_NOT_FOUND, creditorInstitutionCode);
        }
        return result.get();
    }

    private boolean isUnusedCode(Long num, List<Long> alreadyUsedSegregationCodes, List<Long> alreadyUsedApplicationCodes) {
        return !alreadyUsedSegregationCodes.contains(num) && !alreadyUsedApplicationCodes.contains(num);
    }

    private boolean isNotReservedCode(Long num) {
        return !num.equals(47L) && !num.equals(81L) && !num.equals(85L);
    }

    private List<Long> getUsedApplicationCodes(List<PaStazionePa> stazionePaList) {
        return stazionePaList.parallelStream()
                .map(PaStazionePa::getProgressivo)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<Long> getUsedSegregationCodes(List<PaStazionePa> stazionePaList) {
        return stazionePaList.parallelStream()
                .map(PaStazionePa::getSegregazione)
                .filter(Objects::nonNull)
                .toList();
    }

    private LongStream getAvailableCodesForCI(String ciTaxCode) {
        return switch (ciTaxCode) {
            case ROMA_CAPITALE_TAX_CODE -> LongStream.of(49L);
            case ACI_TAX_CODE -> LongStream.of(96L);
            case UNIONECAMERE_TAX_CODE -> LongStream.of(97L);
            default -> LongStream.rangeClosed(0, segregationCodeMaxValue);
        };
    }
}
