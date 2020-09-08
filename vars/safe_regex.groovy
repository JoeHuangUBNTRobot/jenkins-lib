@NonCPS
def call(value, pattern)
{
    return (value =~ pattern).collect {it}
}
