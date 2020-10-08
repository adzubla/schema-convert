import groovy.json.JsonBuilder
import groovy.yaml.YamlSlurper

import java.nio.file.Files
import java.nio.file.Paths

// execute operation on $ref attributes
def findRef(Map map, doit) {
    map.each { key, value ->
        if (key.equals('$ref')) {
            doit(map, key, value)
        } else if (value instanceof List) {
            value.each {
                if (it instanceof Map) {
                    findRef(it, doit)
                }
            }
        } else if (value instanceof Map) {
            findRef(value, doit)
        }
    }
}

def deleteExamples(Map map) {
    Iterator iterator = map.keySet().iterator()
    while (iterator.hasNext()) {
        def key = iterator.next()
        def value = map[key]
        if (key.equals('example')) {
            iterator.remove()
        } else if (value instanceof List) {
            value.each {
                if (it instanceof Map) {
                    deleteExamples(it)
                }
            }
        } else if (value instanceof Map) {
            deleteExamples(value)
        }
    }
}


def inputFile = args[0]
def mongoDbCompat = (args.length > 1) ? "-m".equals(args[1]) : false
def outputPath = 'generated'

def schemas = [:]

println "Reading $inputFile"
def spec = new YamlSlurper().parse(new File(inputFile))

spec.components.schemas.each {
    def schema = it.value

    deleteExamples(schema)

    findRef(schema, { map, key, value -> map.put('$ref', value.replaceAll('#/components/schemas/', '')) })

    if (!mongoDbCompat) {
        schema['$schema'] = 'http://json-schema.org/schema#'
    }
    if (schema['type'] == null) {
        schema['type'] = 'object'
    }

    schemas[(String) it.key] = schema
}

println "Found ${schemas.size()} schemas"

println "Inlining references"

findRef(schemas, { Map map, def key, def value ->
    {
        map.clear()
        map.putAll((Map) schemas.get(value))
    }
})

Files.createDirectories(Paths.get(outputPath));

schemas.each {
    def outputFileName = "$outputPath/${it.key}.json"
    println "Creating $outputFileName"
    new File(outputFileName).delete()

    def schema = it.value
    if (mongoDbCompat) {
        schema = ['$jsonSchema': schema]
    }

    def builder = new JsonBuilder()
    builder schema

    def json = builder.toPrettyString()
    if (mongoDbCompat) {
        json = convertMongoTypes(json)
    }

    new File(outputFileName) << json
}

println 'Done'

def convertMongoTypes(text) {
    return text
            .replaceAll('"type": "integer"', '"bsonType": "int"')
            .replaceAll('"type": "', '"bsonType": "')
}
