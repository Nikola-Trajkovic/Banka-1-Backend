package org.banka1.exchangeservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.banka1.exchangeservice.domains.dtos.option.OptionChainDto;
import org.banka1.exchangeservice.domains.dtos.option.OptionDto;
import org.banka1.exchangeservice.domains.dtos.option.OptionFilterRequest;
import org.banka1.exchangeservice.domains.dtos.option.Response;
import org.banka1.exchangeservice.domains.dtos.user.UserDto;
import org.banka1.exchangeservice.domains.entities.Option;
import org.banka1.exchangeservice.domains.entities.OptionType;
import org.banka1.exchangeservice.domains.entities.Stock;
import org.banka1.exchangeservice.domains.mappers.OptionMapper;
import org.banka1.exchangeservice.repositories.OptionRepository;
import org.banka1.exchangeservice.repositories.StockRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OptionService {

    private final OptionRepository optionRepository;
    private final StockRepository stockRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OptionService(OptionRepository optionRepository, StockRepository stockRepository) {
        this.optionRepository = optionRepository;
        this.stockRepository = stockRepository;
    }

    public void loadOptions(){
        stockRepository.findAll().forEach(stock -> {
            String url = "https://query1.finance.yahoo.com/v7/finance/options/" + stock.getSymbol();
            saveLoadedOptions(url);
        });
    }

    private void saveLoadedOptions(String url) {
        List<Option> options = new ArrayList<>();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .build();

        Response responseOption = null;
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            responseOption = objectMapper.readValue(response.body(), Response.class);

            OptionChainDto optionChainDto = responseOption.getOptionChain();

            optionChainDto.getResult().forEach(resultDto -> {

                resultDto.getOptions().forEach(optionResponseDto -> {

                    optionResponseDto.getCalls().forEach(optionTypeDto -> {

                        Instant instant = Instant.ofEpochMilli(optionTypeDto.getExpiration());
                        LocalDate localDate = instant.atZone(ZoneId.systemDefault()).toLocalDate();

                        Option call = createOption(resultDto.getUnderlyingSymbol(), optionTypeDto.getStrike(),
                                OptionType.CALL, localDate);
                        options.add(call);
                    });

                    optionResponseDto.getPuts().forEach(optionTypeDto -> {
                        Instant instant = Instant.ofEpochMilli(optionTypeDto.getExpiration());
                        LocalDate localDate = instant.atZone(ZoneId.systemDefault()).toLocalDate();

                        Option put = createOption(resultDto.getUnderlyingSymbol(), optionTypeDto.getStrike(),
                                OptionType.PUT, localDate);
                        options.add(put);
                    });
                });
            });
        }catch (Exception e){
            e.printStackTrace();
        }
        optionRepository.saveAll(options);
    }


    public List<OptionDto> getOptions(OptionFilterRequest optionFilterRequest) {
        Iterable<Option> optionIterable = optionRepository.findAll(optionFilterRequest.getPredicate());
        List<Option> options = new ArrayList<>();
        optionIterable.forEach(options::add);

        Stock stock = stockRepository.findBySymbol(optionFilterRequest.getSymbol());
        options.forEach(o -> {
           o.setAsk(stock.getPrice());
           o.setBid(stock.getPrice());
           o.setPrice(stock.getPrice());
        });

        Map<OptionDto, List<Option>> optionMap = options.stream()
                .collect(Collectors.groupingBy(o -> OptionDto.builder().strike(o.getStrike()).optionType(o.getOptionType()).build()));

        List<OptionDto> optionsToReturn = new ArrayList<>();
        for (Map.Entry<OptionDto, List<Option>> entry: optionMap.entrySet()) {
            OptionDto optionDto = entry.getKey();
            List<Option> optionList = entry.getValue();

            optionDto.setOpenInterest(optionList.size());
            OptionMapper.INSTANCE.updateOptionDto(optionDto, optionList.get(0));
            optionsToReturn.add(optionDto);
        }

        return optionsToReturn;
    }

    private Option createOption(String symbol, Double strike, OptionType optionType, LocalDate expirationType) {
        return Option.builder()
                .symbol(symbol)
                .strike(strike)
                .optionType(optionType)
                .expirationDate(expirationType)
                .build();
    }
}
