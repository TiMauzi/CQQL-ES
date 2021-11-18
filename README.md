# CQQL-ES
A plugin for Elasticsearch, which implements the concept of information retrieval based on quantum logic.

## Concept
This plugin was created in the context of a Bachelor's thesis at Brandenburg University of Technology Cottbus-Senftenberg (BTU). The idea of the quantum-based query language CQQL is invented in <i>I. Schmitt. "QQL: A DB&IR Query Language". In: The VLDB Journal 17.1 (Special Issue Paper) (2008), pp. 39–56. DOI: <a href="https://doi.org/10.1007/s00778-007-0070-1">10.1007/s00778-007-0070-1</a></i>. 

## Installing the Plugin
1. Install <a href="https://www.elastic.co/de/downloads/past-releases/elasticsearch-7-10-2">Elasticsearch 7.10.2</a>. This plugin might not work for earlier or later releases or it needs to be adjusted.
2. Download this plugin's ```.zip``` file and place it into a directory of your choice.
3. From the ```bin``` directory within your Elasticsearch download, run ```elasticsearch-plugin install <path to the .zip file>```. 
4. In a warning, you will be asked to give additional permissions. Confirm with ```y```. This plugin uses <a href="https://github.com/axkr/symja_android_library">Symja</a> which needs access to your file system to store temporary files. <br /><b>Note:</b> The installation of the plugin might fail if the confirmation happens too quickly. In that case, try repeating the process from step 3 on.
5. Now you can run Elasticsearch: ```elasticsearch```. The plugin ```search-cqql``` will be loaded automatically after loading all the other, regular modules.

## Using the Plugin
The plugin uses an approach similar to Elasticsearch's <a href="https://www.elastic.co/guide/en/elasticsearch/reference/7.10/query-dsl-bool-query.html">bool query</a>. You simply use the keyword ```commuting_quantum``` in place of ```bool```. Note that there is a ```must```, ```should```, ```must_not```, but no ```filter``` occurence type. Also note that currently there are only the options to use other ```commuting_quantum``` queries, or ```match```/```term```/```match_all```/```match_none``` queries. Other (atomic) query types are not yet implemented.

### Example
```json
{
  "query": {
    "commuting_quantum": {
      "should": [
        {"match": {"text": "fox"}},
        {
          "commuting_quantum":
          {
            "must": [
              {"match": {"text": "eagle"}},
              {"match": {"text": "crocodile"}}
            ]
          }
        }
      ]
    }
  }
}
```

It is also possible to add weights (preferably between 0 and 1) to conditions:

```json
{
    "query": {
      "commuting_quantum": {
        "must": [
          {
            "match" : {
              "text" : "fox"
            }
          },
          {
            "match" : {
              "text" : {
                "query": "crocodile",
                "boost": 0.4
              }
            }
          }
        ]
      }
    }
}
```

## License
CQQL-ES as a whole is published under the GNU GENERAL PUBLIC LICENSE Version 3 (GPL).
* <a href="https://github.com/axkr/symja_android_library#license">Matheclipse (Symja) is published under the GNU GENERAL PUBLIC LICENSE Version 3 (GPL)</a> (this project uses a version from <a href="https://github.com/axkr/symja_android_library/commit/f509ac7f5836c2c2359b348f1542f9028537c1f6>2021/11/12</a>).
* All other files are published under the Apache License, Version 2.0.

## TODO
* As soon as there is a stable release of Symja, the `gradle.build` file might download it directly instead of using a local distribution.
* Other atomic queries (or queries that ought to be treated as such), e.g. `BooleanQuery`/`bool` need to be implemented in `CommutingQuantumQueryBuilder`.
* A new `Similarity` approach should be implemented.
