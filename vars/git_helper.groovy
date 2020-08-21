def sha(ref='HEAD')
{
    return sh_output("git rev-parse ${ref}")
}

def short_sha(ref='HEAD')
{
    return sh_output("git rev-parse --short ${ref}")
}