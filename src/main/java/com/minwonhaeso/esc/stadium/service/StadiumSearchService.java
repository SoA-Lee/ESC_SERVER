package com.minwonhaeso.esc.stadium.service;

import com.minwonhaeso.esc.stadium.model.dto.SearchStadiumDto;
import com.minwonhaeso.esc.stadium.model.entity.StadiumDocument;
import com.minwonhaeso.esc.stadium.repository.StadiumSearchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class StadiumSearchService {
    private final StadiumSearchRepository stadiumSearchRepository;

    @Transactional
    public StadiumDocument save(StadiumDocument stadiumDocument) {
        return stadiumSearchRepository.save(stadiumDocument);
    }

    @Transactional
    public void delete(StadiumDocument stadiumDocument) {
        stadiumSearchRepository.delete(stadiumDocument);
    }

    @Transactional(readOnly = true)
    public Page<StadiumDocument> search(
            SearchStadiumDto.Request request,
            Pageable pageable) {
//        return stadiumSearchRepository.findByName(request.getSearchValue(), pageable);
        return stadiumSearchRepository.searchSimilar(
                new StadiumDocument(), new String[] {request.getSearchValue()}, pageable);
    }
}
