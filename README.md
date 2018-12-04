# Tumblr Archiver

This is a total hack, as-is project to download media from one's tumblr likes to a
directory (`~/tumblr-archive`). It unwinds reblog chains, extracts media from
text posts, has a variety of heuristics for common filetypes, knows how to
request high-quality images instead of small ones, and so on.

## Usage

It's absolutely a gross hack. You've got to go through the oauth workflow at
the repl (see `auth`), and write an edn state file with your oauth credentials
to disk (something like `(save-state! {:creds ...})`). I don't remember exactly
how this works because I only needed to do it once, but all the parts you need
should be there!

Once you've done that, run

```
$ lein run
```

at the console, and it'll download as many likes as it can fetch (subject to
Tumblr's API limits) and write media files to `~/tumblr-archive`. You'll know
it was successful because it throws a tumblr API-limit-exceeded exception. Like
I said, total hack.

## License

Copyright © 2018 Kyle Kingsbury

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
