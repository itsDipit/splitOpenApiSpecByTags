package in.code.dipit.splitByTags;

import com.google.gson.Gson;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.tags.Tag;
import io.swagger.v3.parser.core.models.AuthorizationValue;
import io.swagger.v3.parser.core.models.ParseOptions;
import joptsimple.internal.Strings;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.stream.Collectors;

public class SplitSwaggerOutputByTags {

  private static final Gson gson = new Gson();
  private static final String defaultTag = "Default";
  private static final String schemaComponentString = "#/components/schemas/";
  private static final int schemaComponentStringLength = schemaComponentString.length();

  public static void main(String[] args) throws Exception {

    if (args.length < 2) {
      System.out.println("USAGE: java -jar <jar_name> <input_openAPI_file_path> <output_folder_path>");
      throw new IllegalArgumentException("Expected arguments mismatch. Check usage.");
    }

    String inputFile = args[0];
    String outputFolder = args[1];

    List<AuthorizationValue> authorizationValues = new ArrayList<>();
    ParseOptions parseOptions = null;
    OpenAPI openAPI = new OpenAPIParser()
        .readLocation(inputFile, authorizationValues, parseOptions)
        .getOpenAPI();

    // 1. group by tags - explicit and default
    // 2. build separate OpenAPI object for each tag. Copy the parent's version, info, servers, security, externalDocs, extensions
    // 3. parse through all paths and based on the tag, add them to the respective openAPI object
    // 4. for each path, get the components required from the global components
    // 5. for each path and component, get the extension required from the global extensions -- TODO skipped for now
    // 6. output API json files constructed from respective OpenAPI objects

    // 1. group by tags - explicit and default
    List<String> tags = openAPI.getTags().stream().map(Tag::getName).collect(Collectors.toList());
    tags.add(defaultTag);

    // 2. build separate OpenAPI object for each tag [Do not add info, servers, security]
    Map<String, OpenAPI> openAPIs = new HashMap<>();
    for (String tag: tags) {
      OpenAPI o = new OpenAPI();
      o.setOpenapi(openAPI.getOpenapi());
      o.setInfo(openAPI.getInfo());
      o.setServers(openAPI.getServers());
      o.setSecurity(openAPI.getSecurity());
      o.setExternalDocs(openAPI.getExternalDocs());
      o.setExtensions(openAPI.getExtensions());

      Optional<Tag> opt = openAPI.getTags().stream().filter(t -> t.getName().equals(tag)).findFirst();
      if (opt.isPresent()) {
        o.addTagsItem(opt.get());
      } else {
        o.addTagsItem(new Tag().name(tag));
      }
      openAPIs.put(tag, o);
    }

    Map<String, Set<String>> refsMap = new HashMap<>();  // ref to Tags mapping

    // 3. parse through all paths and based on the tag, add them to the respective openAPI object
    for (Map.Entry<String, PathItem> pie : openAPI.getPaths().entrySet()) {
      String pathName = pie.getKey();
      PathItem pi = pie.getValue();

      processOperation(pathName, pi, pi.getGet(), openAPIs, "Get", refsMap);
      processOperation(pathName, pi, pi.getPost(), openAPIs, "Post", refsMap);
      processOperation(pathName, pi, pi.getPut(), openAPIs, "Put", refsMap);
      processOperation(pathName, pi, pi.getDelete(), openAPIs, "Delete", refsMap);
      processOperation(pathName, pi, pi.getOptions(), openAPIs, "Options", refsMap);
      processOperation(pathName, pi, pi.getHead(), openAPIs, "Head", refsMap);
      processOperation(pathName, pi, pi.getPatch(), openAPIs, "Patch", refsMap);
      processOperation(pathName, pi, pi.getTrace(), openAPIs, "Trace", refsMap);
    }

    // 4. parse the components and map them to the respective openAPI object
    Components components = openAPI.getComponents();

    // 4a. map schema of components
    for (Map.Entry<String, Schema> s : components.getSchemas().entrySet()) {
      dfs(s.getKey(), components.getSchemas(), refsMap);
    }

    for (Map.Entry<String, Schema> s : components.getSchemas().entrySet()) {
      Set<String> tagsMapped;
      if ((tagsMapped = refsMap.get(schemaComponentString + s.getKey())) != null) {
        tagsMapped.forEach(tag -> {
          OpenAPI oa = openAPIs.get(tag);
          Components c = oa.getComponents();
          if (c == null) {
            c = new Components();
          }
          c.addSchemas(s.getKey(), s.getValue());
          oa.setComponents(c);
          openAPIs.put(tag, oa);
        });
      }
    }

    // TODO 4b. map responses, parameters, examples, requestBodies, headers, links, callbacks of components

    // 6. output API json files constructed from respective OpenAPI objects
    for (Map.Entry<String, OpenAPI> e : openAPIs.entrySet()) {
      writeOutputFile(e.getKey().replace(" ", "-") + "-APIs.json",
          outputFolder,
          Json.pretty(e.getValue()));
    }
  }

  private static void dfs(String currRef,
                          Map<String, Schema> schemas,
                          Map<String, Set<String>> refsMap) {
    // 1. get all the neighbor refs of the current ref -> map them to the current ref's tags. If tag was already present in neighbor ref's tags, then don't call dfs for it
    Schema currSchema;
    if ((currSchema = schemas.get(currRef)) != null && currSchema.getProperties() != null) {
      Map<String, Schema> props = (Map<String, Schema>) currSchema.getProperties();

      Set<String> currTags;
      if ((currTags = refsMap.get(schemaComponentString + currRef)) != null) {

        for (Map.Entry<String, Schema> propSchemaPair : props.entrySet()) {
          Schema propSchema = propSchemaPair.getValue();
          String newRef = propSchema.get$ref();
          if (propSchema.getType() != null && propSchema.getType().equals("array")) {
            newRef = ((ArraySchema) propSchema).getItems().get$ref();
          }
          if (Strings.isNullOrEmpty(newRef)) {
            continue;
          }
          boolean flag = false;
          for (String currTag : currTags) {

            if (refsMap.get(newRef) == null || !(refsMap.get(newRef).contains(currTag))) {
              flag = true;
              addToRefMap(newRef, currTag, refsMap);
            }
          }
          if (flag) {
            dfs(newRef.substring(newRef.indexOf(schemaComponentString) + schemaComponentStringLength), schemas, refsMap);
          }
        }
      }
    }
  }

  private static void processOperation(String pathName,
                                       PathItem pi,
                                       Operation op,
                                       Map<String, OpenAPI> openAPIs,
                                       String opType,
                                       Map<String, Set<String>> refsMap) {
    if (op == null) {
      return;
    }

    // get eligible openAPI objects for the given operation
    List<OpenAPI> oas = openAPIs.entrySet().stream()
        .filter(e -> (op.getTags() != null && op.getTags().contains(e.getKey())) ||
            ((op.getTags() == null || op.getTags().isEmpty()) && e.getKey().equals(defaultTag)))
        .map(Map.Entry::getValue)
        .collect(Collectors.toList());

    for (OpenAPI oa : oas) {
      // Add the input operation's Path to each eligible OpenAPI object

      PathItem cpi;
      if (oa.getPaths() == null || (cpi = oa.getPaths().get(pathName)) == null) {
        cpi = new PathItem();

        cpi.summary(pi.getSummary());
        cpi.description(pi.getDescription());
        cpi.servers(pi.getServers());
        cpi.setExtensions(pi.getExtensions());
        cpi.parameters(pi.getParameters());
        cpi.$ref(pi.get$ref());
      }

      switch (opType) {
        case "Get":
          cpi.get(op);
          break;
        case "Post":
          cpi.post(op);
          break;
        case "Put":
          cpi.put(op);
          break;
        case "Delete":
          cpi.delete(op);
          break;
        case "Options":
          cpi.options(op);
          break;
        case "Head":
          cpi.head(op);
          break;
        case "Patch":
          cpi.patch(op);
          break;
        case "Trace":
          cpi.trace(op);
          break;
      }

      // Add path to the current openAPI object
      oa.path(pathName, cpi);

      // Map refs to current openAPI object
      String currOaTag = oa.getTags().get(0).getName();

      if (pi.getParameters() != null) {
        for (Parameter p : pi.getParameters()) {
          addToRefMap(p.get$ref(), currOaTag, refsMap);
        }
      }
      addToRefMap(pi.get$ref(), currOaTag, refsMap);
      if (op.getParameters() != null) {
        for (Parameter p : op.getParameters()) {
          addToRefMap(p.get$ref(), currOaTag, refsMap);
        }
      }
      if (op.getRequestBody() != null) {
        addToRefMap(op.getRequestBody().get$ref(), currOaTag, refsMap);
        op.getRequestBody().getContent().values().forEach(mediaType -> addToRefMap(mediaType.getSchema().get$ref(), currOaTag, refsMap));
      }
      if (op.getCallbacks() != null) {
        op.getCallbacks().forEach((key, value) -> addToRefMap(value.get$ref(), currOaTag, refsMap));
      }
      if (op.getResponses() != null) {
        op.getResponses().values().forEach(apiResponse -> {
          addToRefMap(apiResponse.get$ref(), currOaTag, refsMap);
          apiResponse.getContent().values().forEach(mediaType -> addToRefMap(mediaType.getSchema().get$ref(), currOaTag, refsMap));
        });
      }
    }
  }

  private static void addToRefMap(String ref,
                                  String currTagName,
                                  Map<String, Set<String>> refsMap) {
    if (Strings.isNullOrEmpty(ref)) {
      return;
    }
    Set<String> tags;
    if ((tags = refsMap.get(ref)) == null) {
      tags = new HashSet<>();
    }
    tags.add(currTagName);
    refsMap.put(ref, tags);
  }

  private static void writeOutputFile(String fileName,
                                      String filePath,
                                      String content) {
    try {
      PrintWriter writer = new PrintWriter(filePath + fileName, "UTF-8");
      writer.println(content);
      writer.close();
      System.out.println("Split Output: Created file " + fileName);
    } catch (FileNotFoundException | UnsupportedEncodingException e) {
      System.out.println("Split Output: Failed to create file at " + fileName);
      e.printStackTrace();
    }
  }
}
