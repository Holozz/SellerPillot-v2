package com.sellerpilot.importing;

import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.sellerpilot.common.ApiException;

@RestController
public class ImportController {
    private final CsvParser parser;private final ImportService service;
    public ImportController(CsvParser parser,ImportService service){this.parser=parser;this.service=service;}
    @PostMapping("/stores/{sid}/imports/{kind}")
    public Map<String,Object> upload(@AuthenticationPrincipal Long user,@PathVariable long sid,@PathVariable String kind,@RequestParam MultipartFile file)throws IOException{
        if(file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase().endsWith(".csv"))throw new ApiException(422,"请上传CSV文件");
        CsvParser.Parsed parsed=parser.parse(kind,file.getBytes()); // Parsing completes before the transactional proxy is entered.
        return service.apply(user,sid,parsed);
    }
}
