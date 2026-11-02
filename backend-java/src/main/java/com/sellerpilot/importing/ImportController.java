package com.sellerpilot.importing;

import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.sellerpilot.common.ApiException;
import com.sellerpilot.store.StoreAccess;

@RestController
public class ImportController {
    private final CsvParser parser;private final ImportService service;private final PlatformCsvParser platformParser;private final SourceImportService sourceImports;private final StoreAccess access;
    public ImportController(CsvParser parser,ImportService service,PlatformCsvParser platformParser,SourceImportService sourceImports,StoreAccess access){this.parser=parser;this.service=service;this.platformParser=platformParser;this.sourceImports=sourceImports;this.access=access;}
    @PostMapping("/stores/{sid}/imports/{kind}")
    public Map<String,Object> upload(@AuthenticationPrincipal Long user,@PathVariable long sid,@PathVariable String kind,@RequestParam MultipartFile file)throws IOException{
        if(file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase().endsWith(".csv"))throw new ApiException(422,"请上传CSV文件");
        CsvParser.Parsed parsed=parser.parse(kind,file.getBytes()); // Parsing completes before the transactional proxy is entered.
        return service.apply(user,sid,parsed);
    }
    @PostMapping("/api/v2/stores/{sid}/source-imports/{kind}")
    public Map<String,Object> sourceUpload(@AuthenticationPrincipal Long user,@PathVariable long sid,@PathVariable String kind,@RequestParam MultipartFile file,
                                           @RequestParam(name="confirm_conflicts",defaultValue="false")boolean confirmConflicts)throws IOException{
        if(file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase().endsWith(".csv"))throw new ApiException(422,"请上传CSV文件");
        byte[] bytes=file.getBytes();Map<String,Object> store=access.require(user,sid,"members",false);
        PlatformCsvParser.Parsed parsed=platformParser.parse(kind,String.valueOf(store.get("platform")),String.valueOf(store.get("source_store_key")),bytes);
        return sourceImports.apply(user,sid,parsed,confirmConflicts);
    }
}
